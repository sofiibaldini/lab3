
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
import org.json.JSONObject;
import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {
    private Socket clientSocket;
    private CROSSServer crossServer;
    private String currentUser;
    private BufferedReader in;
    private PrintWriter out;
    
    public ClientHandler(Socket socket, CROSSServer server) {
        this.clientSocket = socket;
        this.crossServer = server;
    }
    
    @Override
    public void run() {
        System.out.println("ClientHandler avviato per: " + clientSocket.getInetAddress());
        
        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream())); PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) 
            {
                this.in = reader;
                this.out = writer;
                boolean loggedout=false;
                
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    JSONObject request = new JSONObject(inputLine);
                    
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
        }

        String username = values.getString("username");
        String password = values.getString("password");
        int result = crossServer.loginUser(username, password, clientSocket.getInetAddress());
        
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
        }
        
        return response;
    }
    
    private JSONObject handleLogout() 
    {
        JSONObject response = new JSONObject();
        if(currentUser == null)
        {
            response.put("response", 104);
            response.put("errorMessage", "user currently logged");
        }
        
            int risultato = crossServer.logoutUser(currentUser);
            
            if (risultato == 100) {
                response.put("response", 100);
                this.currentUser= null;
            }
            } else {
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
            JSONObject response = new JSONObject();
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
            JSONObject response = new JSONObject();
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
            JSONObject response = new JSONObject();
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
}
