
/*
  cosa fa nella pratica questa classe:
  - gestisce la comunicazione con un singolo client tramite socket TCP
    - riceve richieste JSON dal client, le elabora chiamando  crossserver e invia risposte JSON
    - supporta operazioni come registrazione, login, logout, inserimento e cancellazione ordini,
      e richiesta della cronologia dei prezzi
      - nota: clienthandler richiama il timer di inattività in usermanager per resettarlo ad ogni operazione
    - si occcupa anche di controllare se utente è loggato o meno e di far ripartire il timer di inattività
  
 */

 // devi rivedere getpricehistory


package server;

import shared.*;
import server.*;
// aggiungi json message (?)
import org.json.JSONObject;
import java.io.*;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;

public class ClientHandler implements Runnable 
{
    private Socket clientSocket;
    private CROSSServer crossServer;
    private String currentUser;
    private BufferedReader in;
    private PrintWriter out;
    private Timer inactivityTimer;
    private Timer connectionTimer;
    private static final long INACTIVITY_TIMEOUT; // 30 minuti
    private static final long CONNECTION_CHECK_INTERVAL; // 30 secondi
    
    public ClientHandler(Socket socket, CROSSServer server) {
        this.clientSocket = socket;
        this.crossServer = server;
        this.INACTIVITY_TIMEOUT = readInactivityTimeoutFromConfig();
        this.CONNECTION_CHECK_INTERVAL = readConnectionCheckIntervalFromConfig();
    }


    private long readConnectionCheckIntervalFromConfig() {
        try {
            String value = readConfigValue("connection_check_interval");
            if (value != null) {
                return Long.parseLong(value);
            }
        } catch (Exception e) {
            System.err.println("Errore lettura connection_check_interval da config: " + e.getMessage());
        }
        return CONNECTION_CHECK_INTERVAL;
    }

    private static long readInactivityTimeoutFromConfig() {
        try {
            String value = readConfigValue("inactivity_timeout");
            if (value != null) {
                return Long.parseLong(value);
            }
        } catch (Exception e) {
            System.err.println("Errore lettura inactivity_timeout da config: " + e.getMessage());
        }
        return INACTIVITY_TIMEOUT;
    }

    

    private static String readConfigValue(String key) {
        try {
            Path configPath = Paths.get(CONFIG_FILE);
            if (!Files.exists(configPath)) {
                System.err.println("File config non trovato: " + CONFIG_FILE + ". Usando valori default.");
                return null;
            }
            
            List<String> lines = Files.readAllLines(configPath);
            for (String line : lines) {
                line = line.trim();
                
                // Salta commenti e linee vuote
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                // Cerca chiave=valore
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length() + 1).trim();
                }
            }
            
            System.err.println("Chiave '" + key + "' non trovata nel config");
            
        } catch (Exception e) {
            System.err.println("Errore lettura file config '" + CONFIG_FILE + "': " + e.getMessage());
        }
        
        return null;
    }

    @Override
    public void run() {
        System.out.println("ClientHandler avviato per: " + clientSocket.getInetAddress());
        
        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream())); PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) 
            {
                this.in = reader;
                this.out = writer;
                
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    JSONObject request = new JSONObject(inputLine);
                    startInactivityTimer();
                    
                    JSONObject response = processRequest(request);
                    out.println(response.toString());

                }
                
            }
         catch(IOException e) {
            System.err.println("Errore nella gestione del client: " + e.getMessage());
        } finally { 
            // chiudo la disconnessione
            closeConnection();
        }
    }


    private void closeConnection() 
    {
        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
                System.out.println("Connessione chiusa: " + clientSocket.getInetAddress());
            }
        } catch (IOException e) {
            System.err.println("Errore chiusura socket: " + e.getMessage());
        }
    }
    
    private JSONObject processRequest(JSONObject request) {
        try {
            String operation = JSONMessage.getOperation(request);
            JSONObject values = request.getJSONObject("values");
            String username = values.getString("username");
            
            switch (operation) {
                case "register":
                    return handleRegister(values);
                    
                case "login":
                    return handleLogin(values);
                    
                case "logout":
                    return handleLogout();

                case "updateCredentials":
                    return handleUpdateCredentials(values);
                    
                case "insertLimitOrder":
                    return handleInsertLimitOrder(values);
                    
                case "insertMarketOrder":
                    return handleInsertMarketOrder(values);
                    
                case "insertStopOrder":
                    return handleInsertStopOrder(values);
                    
                case "cancelOrder":
                    return handleCancelOrder(values);
                    
                case "getPriceHistory":
                    return handleGetPriceHistory(values);
                    
                default:
                    return createErrorResponse("Operazione non supportata: " + operation);
            }
            
        } catch (Exception e) {
            return createErrorResponse("Errore nel processamento: " + e.getMessage());
        }
    }
    
    private JSONObject handleRegister(JSONObject values) 
    {
        String username = values.getString("username");
        String password = values.getString("password");
        
        int result = crossServer.registerUser(username, password);
        
        JSONObject response = new JSONObject();
        response.put("response", result);
        
        switch (result) {
            case 100: break; // OK
            case 101: response.put("errorMessage", "invalid password"); break;
            case 102: response.put("errorMessage", "username not available"); break;
            default: response.put("errorMessage", "other error cases");
        }
        
        return response;
    }
    
    private JSONObject handleLogin(JSONObject values) {
        JSONObject response = new JSONObject();
        if(currentUser != null)
        {
            response.put("response", 102);
            response.put("errorMessage", "user already logged");
            return response;
        }

        String username = values.getString("username");
        String password = values.getString("password");
        int udpPort = values.optInt("udpPort", -1);
        int result = crossServer.loginUser(username, password, clientSocket, udpPort);

        response.put("response", result);
        
        if (result == 101) {
            response.put("errorMessage", "username/password mismatch or non existent username");
        }
        if (result == 103) {
            response.put("errorMessage", "other error cases");
        }
        else
        {
            this.currentUser = username; // salva l'utente loggato
            startConnectionCheckTimer(); // faccio partire controlli periodici di connessione
        }
        
        return response;
    }
    
    private JSONObject handleLogout() 
    {
        JSONObject response = new JSONObject();
        if(currentUser == null)
        {
            response.put("response", 104);
            response.put("errorMessage", "user not logged in");
            return response;
        }
        
            int risultato = crossServer.logoutUser(currentUser);
            
            if (risultato == 100) {
                response.put("response", 100);
                this.currentUser= null;
                stopAllTimers();
            } else 
            {
                response.put("response", 101);
                response.put("errorMessage", "other error cases");
            }

        return response;
    }

    private JSONObject handleUpdateCredentials(JSONObject values) 
    {

        JSONObject response = new JSONObject();
        if (currentUser != null) // se utente è loggato
        {
            response.put("response", 104);
            response.put("errorMessage", "user currently logged");
            return response;
        }

        String username = values.getString("username");
        String oldPassword = values.getString("old_password");
        String newPassword = values.getString("new_password");
        
        // Chiama il metodo del server
        int result = crossServer.updateCredentials(username, oldPassword, newPassword);
        
        // Costruisce la risposta JSON in base al codice
        response.put("response", result);
        
        switch (result) {
            case 100: // OK
                // Nessun messaggio aggiuntivo necessario per successo
                break;
                
            case 101: // invalid new password
                response.put("errorMessage", "invalid new password");
                break;
                
            case 102: // username/old_password mismatch or non existent username
                response.put("errorMessage", "username/old_password mismatch or non existent username");
                break;
                
            case 103: // new password equal to old one
                response.put("errorMessage", "new password equal to old one");
                break;
                
            case 105: // other error cases
            default:
                response.put("errorMessage", "other error cases");
                break;
        }
        
        return response;
    }
    
    private JSONObject handleInsertLimitOrder(JSONObject values) 
    {
        JSONObject response = new JSONObject();
        if (currentUser == null) {
            response.put("orderId", -1);
            return response;
        }
        
        String type = values.getString("type");
        int size = values.getInt("size");
        int price = values.getInt("price");
        
        try {
            OrderSide side = OrderSide.valueOf(type.toUpperCase());
            long orderId = crossServer.insertLimitOrder(currentUser, side, size, price);
            response.put("orderId", orderId); // sarà -1 in caso di errore
            return response;
            
        } catch (IllegalArgumentException e) {
            response.put("orderId", -1);
            return response;
        }
    }
    
    private JSONObject handleInsertMarketOrder(JSONObject values) 
    {
        JSONObject response = new JSONObject();
        if (currentUser == null) {
            response.put("orderId", -1); // utente non loggato
            return response;
        }
        
        String type = values.getString("type");
        int size = values.getInt("size");
        
        try {
            OrderSide side = OrderSide.valueOf(type.toUpperCase());
            long orderId = crossServer.insertMarketOrder(currentUser, side, size);
            response.put("orderId", orderId);
            return response;
            
        } catch (IllegalArgumentException e) {
            response.put("orderId", -1);
            return response;
        }
    }
    
    private JSONObject handleInsertStopOrder(JSONObject values) 
    {
        JSONObject response = new JSONObject();
        if (currentUser == null) {
            response.put("orderId", -1); // utente non loggato
            return response;
        }
        
        String type = values.getString("type");
        int size = values.getInt("size");
        int price = values.getInt("price");
        
        try {
            OrderSide side = OrderSide.valueOf(type.toUpperCase());
            long orderId = crossServer.insertStopOrder(currentUser, side, size, price);
            response.put("orderId", orderId);
            return response;
            
        } catch (IllegalArgumentException e) {
            response.put("orderId", -1);
            return response;
        }
    }
    
    private JSONObject handleCancelOrder(JSONObject values) 
    {
        if (currentUser == null) {
            JSONObject response = new JSONObject();
            response.put("response", 101);
            response.put("errorMessage", "Utente non autenticato");
            return response;
        }
        
        long orderId = values.getLong("orderId");
        
        // Il controllo che l'ordine appartenga all'utente corrente 
        // viene fatto nel crossServer.cancelOrder
        boolean success = crossServer.cancelOrder(orderId, currentUser);
        
        JSONObject response = new JSONObject();
        if (success) {
            response.put("response", 100); // OK
            // Non mettere errorMessage in caso di successo
        } else {
            response.put("response", 101);
            response.put("errorMessage", "order does not exist or belongs to different user or has already been finalized or other error cases");
        }
        
        return response;
    }


    
    private JSONObject handleGetPriceHistory(JSONObject values) {
        if (currentUser == null) {
            return createErrorResponse("Utente non autenticato");
        }
        
        String month = values.getString("month");
        JSONObject history = crossServer.getPriceHistory(month);
        
        JSONObject response = new JSONObject();
        response.put("history", history);
        return response;
    }
    
    private JSONObject createErrorResponse(String errorMessage) {
        JSONObject response = new JSONObject();
        response.put("response", -1);
        response.put("errorMessage", errorMessage);
        return response;
    }
    
    private void cleanup() {
        if (currentUser != null) {
            crossServer.logoutUser(currentUser);
            this.currentUser=null;
            System.out.println("Client disconnesso: " + currentUser);

        }
        
        try {
            // se la socket non è chiusa (?)
            clientSocket.close();
        } catch (IOException e) {
            System.err.println("Errore nella chiusura del socket: " + e.getMessage());
        }
    }

// =============================================== CONTROLLI DI INATTIVITà ========================

    private void startInactivityTimer() {
        // Cancella timer esistente
        if (inactivityTimer != null) {
            inactivityTimer.cancel();
        }

        if (currentUser == null) {
            return; // Non avviare il timer se nessun utente è loggato
        }

        this.inactivityTimer = new Timer(true);
        TimerTask inactivityTask = new TimerTask() // riguardalo per capirlo bene (?)
        {
            @Override
            public void run() {
                handleInactivityTimeout();
            }
        };
        inactivityTimer.schedule(inactivityTask, INACTIVITY_TIMEOUT);
    
    }
    
    private void handleInactivityTimeout() 
    {
        if (currentUser != null) // se sono loggato
        { 
            crossServer.logoutUser(currentUser); // effettuo logout
            currentUser = null; // resetto utente corrente

            stopAllTimers();
        }  
    }


// ================================== METODI DI CONTROLLO CONNESSIONE ========================


    private void startConnectionCheckTimer() {
            
            // Cancella timer esistente
            if (connectionTimer != null) {
                connectionTimer.cancel();
            }

            this.connectionTimer = new Timer(true);
            connectionTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    checkTcpConnection();
                }
            }, CONNECTION_CHECK_INTERVAL, CONNECTION_CHECK_INTERVAL);
    
    }

    private void checkTcpConnection() 
    {
        if (currentUser == null) 
        {
            // Se non c'è utente loggato, ferma il timer
            stopAllTimers();
            return;
        }
        
        // Controlla direttamente la socket del client
        if (clientSocket == null || clientSocket.isClosed() || !clientSocket.isConnected()) 
        {
            // Esegui logout forzato
            if (currentUser != null) {
                // Notifica il server
                crossServer.logoutUser(currentUser);
            }
            // Pulisci lo stato locale
            this.currentUser = null;
            
            // Ferma i timer
            stopAllTimers();
            
            // Chiudi la socket
            try 
            {
                if (clientSocket != null && !clientSocket.isClosed()) 
                {
                    clientSocket.close();
                }
            } catch (IOException e) 
            {
                System.err.println("Errore chiusura socket: " + e.getMessage());
            }
        }
    }

    private void stopAllTimers() {
        if (inactivityTimer != null) {
            inactivityTimer.cancel();
            inactivityTimer = null;
        }
        if (connectionTimer != null) {
            connectionTimer.cancel();
            connectionTimer = null;
        }
    }
}