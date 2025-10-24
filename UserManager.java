package server;
// riguarda bene tutti gli import (?) e cosa puoi togliere
// metti tutti i metodi sync su this
import shared.*;
import server.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.net.InetAddress;
import java.net.InetAddress;
import java.io.IOException;


public class UserManager {
    // === ATTRIBUTI PRINCIPALI ===
    private Map<String, User> users;
    private Map<String, UserSession> activeSessions;
    private String userDataFile = "data/users.json"; // percorso file JSON utenti
    private final Object lock = new Object();
    private PersistenceManager persistenceManager;


    // === COSTRUTTORE ===
    public UserManager(PersistenceManager persistenceManager) {
        this.users = new HashMap<>();
        this.activeSessions = new HashMap<>();
        this.persistenceManager = persistenceManager;
        loadUsersFromFile();
    }


// ========================================== GESTIONE UTENTI: login, logout ecc  ==============




    public int registerUser(String username, String password) {
        synchronized (this) {
            if (userExists(username)) {
                return 102; // username non disponibile
            }
            
            if (!isValidPassword(password)) {
                return 101; // password invalida
            }
            
            User newUser = new User(username, hashPassword(password));
            users.put(username, newUser);

            return 100; // successo
        }
    }
    
    public int loginUser(String username, String password, InetAddress address, int udpPort) {
        synchronized (this) {
            if (!validateUser(username, password)) {
                return 101; // password sbagliata
            }
            
            UserSession session = new UserSession(username, address, udpPort);
             // creo sessione utente
            activeSessions.put(username, session);
            
            return 100; // successo
        }
    }

    
    public int logoutUser(String username) { // SYNC
        synchronized (this) {
            // non controllo se l'utente è loggato (handler lo fa già)
            activeSessions.remove(username);
                return 100; // successo
        }
    }


    public int updateCredentials(String username, String currentPassword, String newPassword) {
        synchronized (this) { 
            User user = users.get(username);

            if (currentPassword.equals(newPassword)) {
                return 103; // nuova password uguale alla corrente
            }

            if (user == null) {
                return 102; // utente non esistente
            }
            
            if (!verifyPassword(currentPassword, user.getPasswordHash())) {
                return 102; // password corrente errata
            }
            
            if (!isValidPassword(newPassword)) {
                return 101; // nuova password non valida
            }

            user.setPasswordHash(hashPassword(newPassword));

            return 100; // successo
        }
    }



    // === METODI DI VALIDAZIONE ===
    public boolean validateUser(String username, String password) {
        User user = users.get(username);
        return user != null && verifyPassword(password, user.getPasswordHash());
    }
    
    public boolean userExists(String username) {
        return users.containsKey(username);
    }
    
    private boolean isValidPassword(String password) {
        return password != null && password.length() >= 3;
    }

    
    // === SICUREZZA PASSWORD: guarda se ti serve === (?)
    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Errore hashing password", e);
        }
    }
    
    private boolean verifyPassword(String password, String storedHash) {
        String inputHash = hashPassword(password);
        return inputHash.equals(storedHash);
    }



// ==================================== GESTIONE PERSISTENZA =====================================

    
    private void loadUsersFromFile() { // quando viene utilizzato? quando viene creato usermanager (?)
        synchronized (lock) { // serve qua la lock? (è chiamato una sola volta all'inizio)
            try {
                File file = new File(userDataFile); // gli passo il percorso nel costruttore
                if (!file.exists()) {
                    users = new HashMap<>(); // file non esiste, creo mappa vuota
                    return;
                }
                
                String content = new String(Files.readAllBytes(file.toPath())); // leggo tutto il file
                JSONObject json = new JSONObject(content); // parsing JSON
                
                users = new HashMap<>();
                for (String username : json.keySet()) { // controlla che i dati siano passati nel modo in cui li legge (?), qual è la funzione che salva gli utenti? 
                    JSONObject userJson = json.getJSONObject(username);
                    User user = parseUserFromJSON(username, userJson);
                    if (user != null) {
                        users.put(username, user);
                    }
                }
                
                System.out.println("Caricati " + users.size() + " utenti dal file");
                
            } catch (IOException e) {
                System.err.println("Errore nel caricamento utenti: " + e.getMessage());
                users = new HashMap<>();
            }
        }
    }

    private User parseUserFromJSON(String username, JSONObject userJson) 
    {
        try {
            String passwordHash = userJson.getString("passwordHash"); // prendo la password hash
            User user = new User(username, passwordHash); // creo l'utente
            
            if (userJson.has("registrationDate")) { // ci aggiungo la data di registrazione, se c'è
                long timestamp = userJson.getLong("registrationDate");
                user.setRegistrationDate(new Date(timestamp));
            }
            
            return user;
            
        } catch (Exception e) {
            System.err.println("Errore nel parsing utente '" + username + "': " + e.getMessage());
            return null;
        }
    }

    // questo viene chiamato all'inizio di un nuovo giorno da persistenceManager e a shutdown

    private void saveUsersToFile() {
        synchronized (lock) {
            try {
                JSONObject json = new JSONObject();
                
                for (User user : users.values()) {
                    JSONObject userJson = new JSONObject();
                    userJson.put("passwordHash", user.getPasswordHash());
                    userJson.put("registrationDate", user.getRegistrationDate().getTime());
                    
                    json.put(user.getUsername(), userJson);
                }
                
                Files.write(Paths.get(userDataFile), json.toString(2).getBytes());
                
            } catch (IOException e) {
                System.err.println("Errore nel salvataggio utenti: " + e.getMessage());
            }
        }
    }
    



// ==============================================  DA QUA IN POI VEDI COSA è UTILE ====================

        // === METODI UTILITY ===
    public List<String> getLoggedInUsers() {
        return new ArrayList<>(activeSessions.keySet());
    }
    
    public UserSession getUserSession(String username) {
        return activeSessions.get(username);
    }
    
    public int getActiveUsersCount() {
        return activeSessions.size();
    }
    
    public int getTotalUsersCount() {
        return users.size();
    }
    
    // === METODI DI PULIZIA ===
    public void shutdown() { // (?)
        
        // chiudo tutte le sessioni attive
        for (String username : new ArrayList<>(activeSessions.keySet())) {
            logoutUser(username);
        }

        // Salva gli utenti prima di chiudere
        saveUsersToFile();
        System.out.println("usermanager spento correttamente");
    }
    
    // === METODI DI TEST ===
    public void printServerStatus() {
        System.out.println("=== STATO SERVER ===");
        System.out.println("Utenti totali: " + getTotalUsersCount());
        System.out.println("Utenti connessi: " + getActiveUsersCount());
    }
}


