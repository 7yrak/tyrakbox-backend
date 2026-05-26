package com.tyrak.box.controller;

import com.tyrak.box.dto.LoginRequest;
import com.tyrak.box.model.User;
import com.tyrak.box.repository.UserRepository;
import com.tyrak.box.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthController authController;

    private User validUser;

    @BeforeEach
    void setUp() {
        validUser = new User();
        validUser.setUsername("sergio");
        validUser.setPassword("password123");
        validUser.setEmail("sergio@test.com");
    }

    @Test
    void registerUser_MissingEmail_ReturnsBadRequest() {
        // Arrange
        validUser.setEmail(null);
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());

        // Act
        ResponseEntity<?> response = authController.registerUser(validUser);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("correo electrónico es obligatorio"));
    }

    @Test
    void registerUser_ExistingUsername_ReturnsBadRequest() {
        // Arrange
        when(userRepository.findByUsername(validUser.getUsername())).thenReturn(Optional.of(validUser));

        // Act
        ResponseEntity<?> response = authController.registerUser(validUser);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("usuario ya existe"));
    }

    @Test
    void authenticateUser_ValidCredentials_ReturnsOk() {
        // Arrange
        LoginRequest loginRequest = new LoginRequest("sergio", "password123");
        validUser.setId(UUID.randomUUID());
        validUser.setPassword("hashedPassword");

        when(userRepository.findByUsername("sergio")).thenReturn(Optional.of(validUser));
        when(passwordEncoder.matches("password123", "hashedPassword")).thenReturn(true);
        when(tokenProvider.generateToken(anyString(), any(UUID.class))).thenReturn("mock-jwt-token");

        // Act
        ResponseEntity<?> response = authController.authenticateUser(loginRequest);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(tokenProvider, times(1)).generateToken("sergio", validUser.getId());
    }
}
