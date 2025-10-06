package com.tradrbackend.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;

@Configuration
public class FirebaseConfig {

    @Value("${firebase.service-account-path}")
    private String serviceAccountPath;

    @Bean
    public FirebaseApp initializeFirebase() throws IOException {

        InputStream serviceAccount = getClass().getClassLoader().getResourceAsStream(this.serviceAccountPath);

        // System.out.println is removed to clean up startup logs, but this is
        // what it would print: "config/tradrfirebaseservice-firebase-adminsdk-fbsvc-d705d704c0.json"

        if (serviceAccount == null) {
            // Throw a specific error if the file is not found
            throw new IOException("Firebase Service Account JSON file not found in classpath at: " + this.serviceAccountPath + ". Please ensure it is in the src/main/resources/config/ folder.");
        }

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();

        return FirebaseApp.getApps().isEmpty() ? FirebaseApp.initializeApp(options) : FirebaseApp.getInstance();
    }

    @Bean
    public Firestore getFirestore(FirebaseApp firebaseApp) {
        return FirestoreClient.getFirestore(firebaseApp);
    }
}
