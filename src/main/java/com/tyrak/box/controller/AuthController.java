package com.tyrak.box.controller;

import com.tyrak.box.dto.AuthResponse;
import com.tyrak.box.dto.LoginRequest;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.UserRepository;
import com.tyrak.box.security.JwtTokenProvider;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository, JwtTokenProvider tokenProvider, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody User user) {
        if (userRepository.findByUsername(user.getUsername()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El nombre de usuario ya existe."));
        }

        // Validar el correo electrónico ya que la base de datos lo exige (NOT NULL)
        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El correo electrónico es obligatorio."));
        }

        if (user.getFirstName() == null || user.getFirstName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El nombre es obligatorio."));
        }

        if (user.getLastName() == null || user.getLastName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El apellido es obligatorio."));
        }

        user.setPassword(passwordEncoder.encode(user.getPassword()));
        User savedUser = userRepository.save(user);
        
        // Evitar exponer el hash de la contraseña en la respuesta
        savedUser.setPassword(null);

        return ResponseEntity.status(HttpStatus.CREATED).body(savedUser);
    }

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody LoginRequest loginRequest) {
        var userOptional = userRepository.findByUsername(loginRequest.getUsername());

        if (userOptional.isPresent() && passwordEncoder.matches(loginRequest.getPassword(), userOptional.get().getPassword())) {
            User user = userOptional.get();
            String token = tokenProvider.generateToken(user.getUsername(), user.getId());
            return ResponseEntity.ok(new AuthResponse(token, user.getUsername(), user.getFirstName(), user.getLastName(), user.getId()));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Credenciales incorrectas"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        return ResponseEntity.ok(Map.of(
                "userId", user.getId(),
                "username", user.getUsername(),
                "firstName", user.getFirstName(),
                "lastName", user.getLastName(),
                "email", user.getEmail()
        ));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> payload, Authentication authentication) {
        String currentPassword = payload.get("currentPassword");
        String newPassword = payload.get("newPassword");

        if (currentPassword == null || newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Debes enviar la contraseña actual y la nueva."));
        }

        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "La contraseña actual no coincide."));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Contraseña actualizada correctamente."));
    }
}
