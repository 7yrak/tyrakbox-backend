package com.tyrak.box.service;

import com.tyrak.box.config.SyncProperties;
import com.tyrak.box.model.File;
import com.tyrak.box.model.Folder;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.FileRepository;
import com.tyrak.box.repository.FolderRepository;
import com.tyrak.box.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.LocalDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

@Service
public class LocalFolderSyncService {

    private static final int BOOTSTRAP_BATCH_SIZE = 100;

    private final SyncProperties syncProperties;
    private final FileRepository fileRepository;
    private final FolderRepository folderRepository;
    private final UserRepository userRepository;
    private final SyncEventBroadcaster syncEventBroadcaster;
    private final Path storageRoot;
    private final Path sourceBaseRoot;
    private Path sourceRoot;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final BlockingQueue<Runnable> jobQueue = new LinkedBlockingQueue<>(2000);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<WatchKey, Path> watchKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> ignoredPaths = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> recentEvents = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean(false);
    private final AtomicReference<String> pendingRefreshReason = new AtomicReference<>("refresh");
    private final AtomicReference<ScheduledFuture<?>> pendingRefreshTask = new AtomicReference<>();
    private final ConcurrentMap<String, FileSnapshot> syncIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> debouncedEvents = new ConcurrentHashMap<>();

    private WatchService watchService;
    private User syncUser;
    private volatile String lastEvent = "Inicializando";
    private volatile String lastError = "";
    private volatile String currentTask = "";
    private volatile long currentTaskBytes = 0L;
    private volatile long currentTaskTotalBytes = 0L;
    private volatile int currentTaskProgress = 0;
    private volatile long currentTaskUpdatedAt = 0L;

    public LocalFolderSyncService(
            SyncProperties syncProperties,
            FileRepository fileRepository,
            FolderRepository folderRepository,
            UserRepository userRepository,
            SyncEventBroadcaster syncEventBroadcaster,
            @Value("${storage.location:D:/workspace/herramientas/data-tyrak-box}") String storageLocation,
            @Value("${sync.source-location:D:/workspace/herramientas/TyrakSync}") String sourceLocation
    ) {
        this.syncProperties = syncProperties;
        this.fileRepository = fileRepository;
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
        this.syncEventBroadcaster = syncEventBroadcaster;
        this.storageRoot = Paths.get(storageLocation);
        this.sourceBaseRoot = Paths.get(sourceLocation);
        this.sourceRoot = this.sourceBaseRoot;
    }

    @PostConstruct
    public void start() {
        if (!syncProperties.isEnabled()) {
            return;
        }

        try {
            syncUser = resolveSyncUser();
            sourceRoot = sourceBaseRoot.resolve(syncUser.getId().toString());
            Files.createDirectories(sourceRoot);
            watchService = sourceRoot.getFileSystem().newWatchService();
            preloadSyncIndex();
            migrateSyncedFilesToCurrentUser();
            registerAll(sourceRoot);
            running.set(true);
            bootstrapExistingFiles();
            executor.submit(this::watchLoop);
            executor.submit(this::jobLoop);
            scheduler.scheduleWithFixedDelay(this::reconcileLightlySafely, 5, 60, TimeUnit.MINUTES);
            lastEvent = "Sincronización activa";
        } catch (IOException e) {
            throw new RuntimeException("No se pudo iniciar la sincronización local", e);
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    public Path getSourceRoot() {
        return sourceRoot;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new java.util.HashMap<>();
        status.put("enabled", syncProperties.isEnabled());
        status.put("running", running.get());
        status.put("sourceLocation", sourceRoot.toString());
        status.put("sourceBaseLocation", sourceBaseRoot.toString());
        status.put("storageLocation", storageRoot.toString());
        status.put("syncUsername", syncProperties.getUsername());
        status.put("syncUserId", syncUser != null ? syncUser.getId().toString() : null);
        status.put("lastEvent", lastEvent);
        status.put("lastError", lastError);
        status.put("currentTask", currentTask);
        status.put("currentTaskBytes", currentTaskBytes);
        status.put("currentTaskTotalBytes", currentTaskTotalBytes);
        status.put("currentTaskProgress", currentTaskProgress);
        status.put("currentTaskUpdatedAt", currentTaskUpdatedAt);
        status.put("recentEvents", List.copyOf(recentEvents));
        return status;
    }

    public void broadcastStatus() {
        syncEventBroadcaster.broadcastStatus(getStatus());
    }

    private void broadcastRefreshAfterCommit(String reason) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    requestRefresh(reason);
                }
            });
            return;
        }
        requestRefresh(reason);
    }

    private void requestRefresh(String reason) {
        pendingRefreshReason.set(reason);
        ScheduledFuture<?> previous = pendingRefreshTask.getAndSet(
                scheduler.schedule(() -> {
                    try {
                        syncEventBroadcaster.broadcastEvent("refresh", pendingRefreshReason.get());
                    } finally {
                        refreshQueued.set(false);
                        pendingRefreshTask.set(null);
                    }
                }, 1200, TimeUnit.MILLISECONDS)
        );

        refreshQueued.set(true);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    public void mirrorUploadFromApp(File file, byte[] content) {
        // Ya no se duplica el archivo dentro de la carpeta vigilada.
        // El archivo físico se guarda una sola vez desde FileService.
        if (!syncProperties.isEnabled()) return;
        lastEvent = "Subida registrada: " + file.getName();
        lastError = "";
        broadcastStatus();
    }

    public void mirrorFolderFromApp(Folder folder) {
        if (!syncProperties.isEnabled()) return;
        try {
            Path dir = sourceRoot.resolve(resolveFolderRelativePath(folder));
            ignorePath(resolveFolderRelativePath(folder));
            Files.createDirectories(dir);
            lastEvent = "Carpeta reflejada: " + folder.getName();
            lastError = "";
            broadcastStatus();
        } catch (IOException e) {
            lastError = "No se pudo reflejar carpeta: " + e.getMessage();
            broadcastStatus();
        }
    }

    public void mirrorDeleteFromApp(File file) {
        if (!syncProperties.isEnabled()) return;
        try {
            String relative = resolveRelativePath(file);
            ignorePath(relative);
            Files.deleteIfExists(sourceRoot.resolve(relative));
            lastEvent = "Eliminado desde la web: " + file.getName();
            lastError = "";
            broadcastStatus();
        } catch (IOException e) {
            lastError = "No se pudo reflejar borrado: " + e.getMessage();
            broadcastStatus();
        }
    }

    public void mirrorDeleteFolderFromApp(Folder folder) {
        if (!syncProperties.isEnabled()) return;
        try {
            Path dir = sourceRoot.resolve(resolveFolderRelativePath(folder));
            ignorePath(resolveFolderRelativePath(folder));
            if (Files.exists(dir)) {
                Files.walk(dir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {}
                        });
            }
            for (File syncedFile : fileRepository.findSyncedFilesForUser(syncUser.getId())) {
                if (syncedFile.getSyncPath() != null && syncedFile.getSyncPath().startsWith(resolveFolderRelativePath(folder) + "/")) {
                    Files.deleteIfExists(sourceRoot.resolve(resolveRelativePath(syncedFile)));
                }
            }
            lastEvent = "Carpeta eliminada desde la web: " + folder.getName();
            lastError = "";
            broadcastStatus();
        } catch (IOException e) {
            lastError = "No se pudo reflejar borrado de carpeta: " + e.getMessage();
            broadcastStatus();
        }
    }

    private User resolveSyncUser() {
        String username = syncProperties.getUsername() != null && !syncProperties.getUsername().isBlank()
                ? syncProperties.getUsername()
                : "sync";

        return userRepository.findByUsername(username)
                .orElseGet(() -> {
                    User user = new User();
                    user.setUsername(username);
                    user.setEmail("sync@local.tyrak");
                    user.setPassword("sync-local-account");
                    return userRepository.save(user);
                });
    }

    private void migrateSyncedFilesToCurrentUser() {
        // No reasignamos archivos existentes a otro usuario para evitar mezclar datos entre cuentas.
        recordEvent("Sincronización preparada para el usuario " + syncUser.getUsername());
        broadcastStatus();
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                return;
            }

            Path dir = watchKeys.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == java.nio.file.StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                Path name = (Path) event.context();
                Path child = dir.resolve(name);
                String relative = normalizeSyncPath(child);
                if (isIgnored(relative)) {
                    continue;
                }

                if (kind == ENTRY_CREATE) {
                    queueDebouncedFileEvent(child, "create");
                } else if (kind == ENTRY_MODIFY) {
                    queueDebouncedFileEvent(child, "modify");
                } else if (kind == ENTRY_DELETE) {
                    queueDebouncedFileEvent(child, "delete");
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                watchKeys.remove(key);
            }
        }
    }

    private void registerAll(Path start) throws IOException {
        Files.walk(start)
                .filter(Files::isDirectory)
                .forEach(path -> {
                    try {
                        registerDirectory(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private void registerDirectory(Path dir) throws IOException {
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        watchKeys.put(key, dir);
    }

    private void bootstrapExistingFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walk(sourceRoot)
                .filter(Files::isRegularFile)
                .filter(path -> !isInternalTempPath(path))
                .forEach(files::add);

        for (int i = 0; i < files.size(); i += BOOTSTRAP_BATCH_SIZE) {
            List<Path> batch = files.subList(i, Math.min(i + BOOTSTRAP_BATCH_SIZE, files.size()));
            submitJob(() -> importBootstrapBatch(new ArrayList<>(batch)));
        }
    }

    private void importBootstrapBatch(List<Path> batch) {
        for (Path path : batch) {
            try {
                importFileIfNeeded(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void jobLoop() {
        while (running.get() || !jobQueue.isEmpty()) {
            try {
                Runnable job = jobQueue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (job != null) {
                    job.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                lastError = "Error procesando cola: " + e.getMessage();
            }
        }
    }

    private void submitJob(Runnable job) {
        if (!running.get()) {
            job.run();
            return;
        }
        if (!jobQueue.offer(job)) {
            lastError = "La cola de sincronización está llena";
        }
    }

    private void queueDebouncedFileEvent(Path path, String eventType) {
        if (isInternalTempPath(path)) {
            return;
        }

        String syncPath = normalizeSyncPath(path);
        ScheduledFuture<?> previous = debouncedEvents.get(syncPath);
        if (previous != null) {
            previous.cancel(false);
        }

        ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
            try {
                if (!running.get()) {
                    return;
                }
                if ("delete".equals(eventType)) {
                    submitJob(() -> handleDelete(path));
                } else if ("create".equals(eventType)) {
                    submitJob(() -> handleCreate(path));
                } else {
                    submitJob(() -> handleModify(path));
                }
            } finally {
                debouncedEvents.remove(syncPath);
            }
        }, 450, TimeUnit.MILLISECONDS);

        debouncedEvents.put(syncPath, scheduled);
    }

    private void handleCreate(Path path) {
        try {
            if (isInternalTempPath(path)) {
                return;
            }
            if (Files.isDirectory(path)) {
                registerAll(path);
                recordEvent("Carpeta detectada: " + normalizeSyncPath(path));
                broadcastStatus();
                broadcastRefreshAfterCommit("folder-created");
                return;
            }

            importFileIfNeeded(path);
        } catch (IOException e) {
            throw new RuntimeException("Error sincronizando alta: " + path, e);
        }
    }

    private void handleModify(Path path) {
        try {
            if (isInternalTempPath(path)) {
                return;
            }
            if (Files.isDirectory(path) || !Files.exists(path)) {
                return;
            }
            importFileIfNeeded(path);
        } catch (IOException e) {
            throw new RuntimeException("Error sincronizando cambio: " + path, e);
        }
    }

    private void handleDelete(Path path) {
        if (isInternalTempPath(path)) {
            return;
        }
        String syncPath = normalizeSyncPath(path);
        lastEvent = "Borrado detectado: " + syncPath;
        recordEvent("Borrado detectado: " + syncPath);
        broadcastStatus();
        FileSnapshot snapshot = syncIndex.get(syncPath);
        if (snapshot != null && !snapshot.deleted) {
            fileRepository.findLatestBySyncPathAndUserId(syncPath, syncUser.getId())
                    .ifPresent(file -> {
                        file.setIsDeleted(true);
                        file.setSyncedAt(LocalDateTime.now());
                        fileRepository.save(file);
                        syncIndex.put(syncPath, FileSnapshot.from(file));
                    });
        }
        broadcastRefreshAfterCommit("file-deleted");
    }

    private void reconcileLightlySafely() {
        if (!running.get()) {
            return;
        }
        try {
            reconcileLightly();
        } catch (Exception e) {
            lastError = "Error en reconciliación liviana: " + e.getMessage();
        }
    }

    private void reconcileLightly() {
        for (Map.Entry<String, FileSnapshot> entry : syncIndex.entrySet()) {
            String syncPath = entry.getKey();
            FileSnapshot snapshot = entry.getValue();
            Path physical = sourceRoot.resolve(syncPath);

            boolean exists = Files.exists(physical);
            if (!exists && !snapshot.deleted) {
                fileRepository.findLatestBySyncPathAndUserId(syncPath, syncUser.getId())
                        .ifPresent(file -> {
                            file.setIsDeleted(true);
                            file.setSyncedAt(LocalDateTime.now());
                            fileRepository.save(file);
                            syncIndex.put(syncPath, new FileSnapshot(snapshot.sizeBytes, snapshot.sourceLastModified, true));
                        });
                continue;
            }

            if (exists && snapshot.deleted) {
                submitJob(() -> {
                    try {
                        importFileIfNeeded(physical);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    @Transactional
    private synchronized void importFileIfNeeded(Path path) throws IOException {
        if (!Files.exists(path) || Files.isDirectory(path)) {
            return;
        }

        String syncPath = normalizeSyncPath(path);
        long size = Files.size(path);
        LocalDateTime sourceModified = LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), java.time.ZoneId.systemDefault());
        FileSnapshot snapshot = syncIndex.get(syncPath);
        if (snapshot != null
                && !snapshot.deleted
                && snapshot.sizeBytes != null
                && snapshot.sizeBytes == size
                && snapshot.sourceLastModified != null
                && snapshot.sourceLastModified.equals(sourceModified)) {
            return;
        }
        importFile(path, syncPath, size, sourceModified, Optional.empty());
    }

    @Transactional
    public synchronized void importFile(Path path) throws IOException {
        if (!Files.exists(path) || Files.isDirectory(path)) {
            return;
        }

        String syncPath = normalizeSyncPath(path);
        String fileName = path.getFileName().toString();
        long size = Files.size(path);
        LocalDateTime sourceModified = LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), java.time.ZoneId.systemDefault());
        Folder folder = resolveFolder(path.getParent());

        currentTask = fileName;
        currentTaskTotalBytes = size;
        currentTaskBytes = 0L;
        currentTaskProgress = 0;
        currentTaskUpdatedAt = System.currentTimeMillis();
        broadcastStatus();

        Optional<File> existing = fileRepository.findLatestBySyncPathAndUserId(syncPath, syncUser.getId());
        if (existing.isPresent()) {
            File current = existing.get();
            if (Boolean.FALSE.equals(current.getIsDeleted())
                    && current.getSizeBytes() != null
                    && current.getSizeBytes() == size
                    && current.getSourceLastModified() != null
                    && current.getSourceLastModified().equals(sourceModified)) {
                broadcastStatus();
                return;
            }
        }

        File model = existing.orElseGet(File::new);
        model.setName(fileName);
        model.setMimeType(Files.probeContentType(path) != null ? Files.probeContentType(path) : "application/octet-stream");
        model.setSizeBytes(size);
        model.setUser(syncUser);
        model.setFolder(folder);
        model.setIsDeleted(false);
        model.setSyncPath(syncPath);
        model.setContentHash(null);
        model.setSyncedAt(LocalDateTime.now());
        model.setSourceLastModified(sourceModified);

        fileRepository.save(model);
        syncIndex.put(syncPath, FileSnapshot.from(model));
        lastEvent = "Sincronizado: " + syncPath;
        recordEvent("Sincronizado: " + syncPath + " (" + size + " bytes)");
        lastError = "";
        currentTaskBytes = size;
        currentTaskProgress = 100;
        currentTaskUpdatedAt = System.currentTimeMillis();
        broadcastStatus();
        broadcastRefreshAfterCommit("file-synced");
        executor.submit(() -> {
            try {
                TimeUnit.SECONDS.sleep(3);
                if (System.currentTimeMillis() - currentTaskUpdatedAt >= 2500L) {
                    currentTask = "";
                    currentTaskBytes = 0L;
                    currentTaskTotalBytes = 0L;
                    currentTaskProgress = 0;
                    broadcastStatus();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Transactional
    private void importFile(Path path, String syncPath, long size, LocalDateTime sourceModified, Optional<File> existing) throws IOException {
        String fileName = path.getFileName().toString();
        Folder folder = resolveFolder(path.getParent());

        currentTask = fileName;
        currentTaskTotalBytes = size;
        currentTaskBytes = 0L;
        currentTaskProgress = 0;
        currentTaskUpdatedAt = System.currentTimeMillis();
        broadcastStatus();

        File model = existing.orElseGet(File::new);
        model.setName(fileName);
        model.setMimeType(Files.probeContentType(path) != null ? Files.probeContentType(path) : "application/octet-stream");
        model.setSizeBytes(size);
        model.setUser(syncUser);
        model.setFolder(folder);
        model.setIsDeleted(false);
        model.setSyncPath(syncPath);
        model.setContentHash(null);
        model.setSyncedAt(LocalDateTime.now());
        model.setSourceLastModified(sourceModified);

        fileRepository.save(model);
        syncIndex.put(syncPath, FileSnapshot.from(model));
        lastEvent = "Sincronizado: " + syncPath;
        recordEvent("Sincronizado: " + syncPath + " (" + size + " bytes)");
        lastError = "";
        currentTaskBytes = size;
        currentTaskProgress = 100;
        currentTaskUpdatedAt = System.currentTimeMillis();
        broadcastStatus();
        broadcastRefreshAfterCommit("file-synced");
    }

    private Folder resolveFolder(Path parentPath) {
        if (parentPath == null || sourceRoot.equals(parentPath)) {
            return null;
        }

        Path relative = sourceRoot.relativize(parentPath);
        Folder current = null;
        for (Path segment : relative) {
            current = findOrCreateFolder(segment.toString(), syncUser, current != null ? current.getId() : null);
        }
        return current;
    }

    private Folder findOrCreateFolder(String name, User user, UUID parentId) {
        Optional<Folder> existing = parentId == null
                ? folderRepository.findByNameAndUser_IdAndParentIsNull(name, user.getId())
                : folderRepository.findByNameAndUser_IdAndParent_Id(name, user.getId(), parentId);

        if (existing.isPresent()) {
            return existing.get();
        }

        Folder parent = parentId != null ? folderRepository.findById(parentId).orElse(null) : null;
        Folder newFolder = new Folder();
        newFolder.setName(name);
        newFolder.setUser(user);
        newFolder.setParent(parent);
        newFolder.setIsDeleted(false);
        return folderRepository.save(newFolder);
    }

    private String normalizeSyncPath(Path path) {
        Path relative = sourceRoot.relativize(path);
        return relative.toString().replace("\\", "/");
    }

    private boolean isInternalTempPath(Path path) {
        Path relative = sourceRoot.relativize(path);
        return !relative.toString().isEmpty() && relative.getNameCount() > 0 && "chunks".equals(relative.getName(0).toString());
    }

    private String resolveRelativePath(File file) {
        if (file.getSyncPath() != null && !file.getSyncPath().isBlank()) {
            return file.getSyncPath();
        }
        return buildFolderPath(file.getFolder()) + file.getName();
    }

    private String resolveFolderRelativePath(Folder folder) {
        String prefix = buildFolderPath(folder);
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private String buildFolderPath(Folder folder) {
        if (folder == null) return "";
        String parent = buildFolderPath(folder.getParent());
        return parent + folder.getName() + "/";
    }

    private void ignorePath(String relativePath) {
        ignoredPaths.put(relativePath.replace("\\", "/"), Boolean.TRUE);
    }

    private boolean isIgnored(String relativePath) {
        return ignoredPaths.remove(relativePath.replace("\\", "/")) != null;
    }

    private void preloadSyncIndex() {
        syncIndex.clear();
        for (File file : fileRepository.findSyncedFilesForUser(syncUser.getId())) {
            if (file.getSyncPath() != null && !file.getSyncPath().isBlank()) {
                syncIndex.put(file.getSyncPath(), FileSnapshot.from(file));
            }
        }
    }

    private void recordEvent(String message) {
        lastEvent = message;
        recentEvents.addFirst(message);
        while (recentEvents.size() > 12) {
            recentEvents.removeLast();
        }
        currentTaskUpdatedAt = System.currentTimeMillis();
        broadcastStatus();
    }

    private static final class FileSnapshot {
        private final Long sizeBytes;
        private final LocalDateTime sourceLastModified;
        private final boolean deleted;

        private FileSnapshot(Long sizeBytes, LocalDateTime sourceLastModified, boolean deleted) {
            this.sizeBytes = sizeBytes;
            this.sourceLastModified = sourceLastModified;
            this.deleted = deleted;
        }

        private static FileSnapshot from(File file) {
            return new FileSnapshot(file.getSizeBytes(), file.getSourceLastModified(), Boolean.TRUE.equals(file.getIsDeleted()));
        }
    }

}
