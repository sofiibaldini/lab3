package server;
// riguarda bene tutti gli import (?)
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.net.InetAddress;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.io.IOException;
import java.net.Socket; // per UserSession
import java.util.Timer; // per i timer
import java.util.TimerTask; // per i timer

public enum OrderType { LIMIT, MARKET } // questi non so se vanno qua (?)
public enum Side { BUY, SELL }

public class UserManager {
    // === ATTRIBUTI PRINCIPALI ===
    private Map<String, User> users;
    private Map<String, UserSession> activeSessions;
    private String userDataFile; // percorso file JSON utenti
    private final Object lock = new Object();
    private DatagramSocket udpSocket; // socket UDP per notifiche, condivisa (?)
    private int udpPort = 4445; // non so se andrà cambiata, ma forse no se la mando a ogni utente che si connette
    private ClientHandler handler; // riferimento al clienthandler per richiedere logout in caso di inattività
    
    // === COSTRUTTORE ===
    public UserManager(String userDataFile, PersistenceManager persistenceManager, NotificationService notificationService) {
        this.userDataFile = userDataFile;
        this.users = new HashMap<>();
        this.activeSessions = new HashMap<>();
        this.persistenceManager = persistenceManager;
        this.notificationService = notificationService;
        initializeUdpSocket();
        loadUsersFromFile();
    }
    
    // === INIZIALIZZAZIONE SOCKET UDP ===
    private void initializeUdpSocket() {
        try {
            udpSocket = new DatagramSocket();
        } catch (SocketException e) {
            System.err.println("Errore nell'apertura socket UDP: " + e.getMessage());
        }
    }
    
// =======================================================   UTENTE  ========================
    
    public static class User {
        private String username;
        private String passwordHash;
        private Date registrationDate;
        
        public User(String username, String passwordHash) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.registrationDate = new Date();
        }
        
        // Getters and setters
        public String getUsername() { return username; }
        public String getPasswordHash() { return passwordHash; }
        public Date getRegistrationDate() { return registrationDate; }
        
        public void setRegistrationDate(Date date) { this.registrationDate = date; }
    }
    
//=========================================================  SESSIONE UTENTE ========================

    public static class UserSession {
        private String username;
        private InetAddress clientAddress;
        private Date loginTime;
        private Date lastActivity;
        private Socket tcpSocket;
        private final long INACTIVITY_TIMEOUT = 30 * 60 * 1000; // 30 minuti
        private Timer inactivityTimer; // TIMER INATTIVITÀ
        private Timer connectionTimer; // TIMER CONTROLLO CONNESSIONE
        
        public UserSession(String username, InetAddress clientAddress, Socket tcpSocket) {
            this.username = username;
            this.clientAddress = clientAddress;
            this.loginTime = new Date();
            this.lastActivity = new Date();
            this.tcpSocket = tcpSocket;
        }
        
        // Getters
        public String getUsername() { return username; }
        public InetAddress getClientAddress() { return clientAddress; }
        public Date getLoginTime() { return loginTime; }
        public Date getLastActivity() { return lastActivity; }
        public Socket getTcpSocket() { return tcpSocket; }
        public void updateLastActivity() { this.lastActivity = new Date(); }
        public Timer getInactivityTimer() { return inactivityTimer; }
        public Timer getConnectionTimer() { return connectionTimer; }


        // setters timer inattività: prima creo il timer, chiamo questo metodo per salvarlo nella sessione
        public void setInactivityTimer(Timer timer) { this.inactivityTimer = timer; }
        public void setConnectionTimer(Timer timer) { this.connectionTimer = timer; }

        public boolean isUserLoggedIn(String username) {
            synchronized (sessionLock) {
                UserSession session = activeSessions.get(username);
                
                if (session == null) {
                    return false;
                }
                if (session.getClientAddress().equals(this.getClientAddress())) {
                    return true;
                }
                return false;
            }
        }
    }
    

    
// =========================================================  ORDINE  ========================

    public static class Order {
        private String orderId;
        private Side side;
        private OrderType type;
        private double price;
        private int size;
        private int remainingSize;
        private int TempRemainingSize; 
        private String username;
        
        public Order(String orderId, Side side, OrderType type, 
                    double price, int size, String username) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.side = side;
            this.type = type;
            this.price = price;
            this.size = size;
            this.remainingSize = size;
            this.username = username;
            this.timestamp = System.currentTimeMillis();
        }
        
        // Getters
        public String getOrderId() { return orderId; }
        public String getSymbol() { return symbol; }
        public Side getSide() { return side; }
        public OrderType getType() { return type; }
        public double getPrice() { return price; }
        public int getSize() { return size; }
        public String getUsername() { return username; }
        public long getTimestamp() { return timestamp; }
        public int getRemainingSize() { return remainingSize; }
        public int getTempRemainingSize() { return tempRemainingSize; }
        
        // Setters per modifica quantità
        public void setRemainingSize(int size) { this.remainingSize = size; }
        public void setTempRemainingSize(int size) { this.tempRemainingSize = size; }
    }
    
// =========================================================  TRADE  ========================

    public static class Trade {
        private String buyer;
        private String seller;
        private double price;
        private int size;
        private String buyOrderId;
        private String sellOrderId;
        private long timestamp;
        
        public Trade(String buyOrderId, String sellOrderId, double price, int size, String buyer, String seller) {
            this.buyer = buyer;
            this.seller = seller;
            this.price = price;
            this.size = size;
            this.buyOrderId = buyOrderId;
            this.sellOrderId = sellOrderId;
            this.timestamp = System.currentTimeMillis();
        }
        
        // Getters
        public String getTradeId() { return tradeId; }
        public String getBuyer() { return buyer; }
        public String getSeller() { return seller; }
        public double getPrice() { return price; }
        public int getSize() { return size; }
        public String getBuyOrderId() { return buyOrderId; }
        public String getSellOrderId() { return sellOrderId; }
        public long getTimestamp() { return timestamp; }
    }
    
// ========================================== GESTIONE UTENTI: login, logout ecc  ==============


    public int registerUser(String username, String password) {
        synchronized (lock) {
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
    
    public int loginUser(String username, String password, InetAddress address, Socket tcpSocket) {
        synchronized (lock) {
            if (!validateUser(username, password)) {
                return 101; // password sbagliata
            }
            
            if (isUserLoggedIn(username)) {
                return 102; // utente già loggato
            }
            
            UserSession session = new UserSession(username, address, tcpSocket);
            activeSessions.put(username, session);
            startInactivityTimer(username);
            startConnectionCheckTimer(username);
            
            return 100; // successo
        }
    }
    
    public int logoutUser(String username) {
        synchronized (lock) {
            // controllo se l'utente è loggato
            if (isUserLoggedIn(username))
            {
                 // chiudo la socket se è aperta, cancello i timer e rimuovo la sessione
                UserSession session = activeSessions.get(username);
                closeSession(); // non so se va bene così (?)
                return 100; // successo
            }
            else
            {
               return 101; // utente non loggato
            }
        }
    }

    // Metodo per  cancellare i timer, rimuovere sessione
    public void closeSession() // vedi se tenerlo privato (?)
    {
        // Chiudi timer
        if (inactivityTimer != null) 
        {
            inactivityTimer.cancel();
            inactivityTimer = null;
        }           
        if (connectionTimer != null) 
        {
            connectionTimer.cancel();
            connectionTimer = null;
        }
        // Rimuovi sessione
        synchronized (lock) 
            {activeSessions.remove(username); }
    }

    public int updateCredentials(String username, String currentPassword, String newPassword) {
        synchronized (lock) { 
            User user = users.get(username);

            if (isUserLoggedIn(username)) {
                return 104; // utente loggato, non può cambiare password
            }

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
            
            user.passwordHash = hashPassword(newPassword);
            
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
    

// ================================== METODI DI CONTROLLO CONNESSIONE E INATTIVITà ========================

    // =============================================== CONTROLLI DI INATTIVITà ========================

    private void startInactivityTimer(String username) {
        Timer timer = new Timer(true);
        UserSession session = activeSessions.get(username);
        session.setInactivityTimer(timer);

        TimerTask inactivityTask = new TimerTask() // riguardalo per capirlo bene (?)
        {
            @Override
            public void run() {
                handleInactivityTimeout(username);
            }
        };
        timer.schedule(inactivityTask, UserSession.INACTIVITY_TIMEOUT);
    
    }
    
    private void cancelInactivityTimer(String username) {
        UserSession session = activeSessions.get(username);
        if (session != null && session.getInactivityTimer() != null) {
            session.getInactivityTimer().cancel(); // cancel cosa chiama qua? è un metodo del timertask? (?)
        }
    }
    
    private void handleInactivityTimeout(String username) {
        synchronized (lock) {
            if (isUserLoggedIn(username)) {
                System.out.println("Timeout inattività per utente: " + username);
                logoutUser(username);
            }
        }
    }


    private void startConnectionCheckTimer(String username) {
        synchronized (lock) { 
            UserSession session = activeSessions.get(username);
            if (session == null) return;
            
            // Cancella timer esistente
            cancelConnectionCheckTimer(username);
            
            Timer connectionTimer = new Timer("ConnectionCheck-" + username, true);
            session.setConnectionTimer(connectionTimer);
            connectionTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    checkTcpConnection(username);
                }
            }, CONNECTION_CHECK_INTERVAL, CONNECTION_CHECK_INTERVAL);

    }


    private void cancelConnectionCheckTimer(String username) {
        synchronized (lock) {
            UserSession session = activeSessions.get(username);
            if (session != null && session.getConnectionTimer() != null) {
                session.getConnectionTimer().cancel();
                session.setConnectionTimer(null);
                System.out.println("Timer controllo connessione cancellato per: " + username);
            }
        }
    }


    private void checkTcpConnection(String username) 
    {
        synchronized (lock) 
        {
            if (!isUserLoggedIn(username)) {
                cancelConnectionCheckTimer(username);
                return;
            }
            
            UserSession session = activeSessions.get(username);
            Socket tcpSocket = session.getTcpSocket();
            
            if (tcpSocket == null || tcpSocket.isClosed() || !tcpSocket.isConnected()) {
                System.out.println("Controllo connessione: TCP persa per " + username);
                logoutUser(username);
            } else {
                System.out.println("Controllo connessione: OK per " + username);
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
        System.out.println("Socket UDP: " + (udpSocket != null && !udpSocket.isClosed() ? "ATTIVO" : "CHIUSO"));
    }
}

}