/*
 * ServerMain.java
 * cosa fa questa classe:
 * - avvia il server CROSS, ascolta le connessioni in entrata sulla porta specificata
 * - per ogni connessione accettata, crea un nuovo ClientHandler in un thread separato
 * - utilizza un thread pool per gestire efficientemente più connessioni client contemporaneamente
 * - legge la configurazione della porta da un file di configurazione
 * 
 */


 // aggiungi riferimenti a userManager, orderBook, persistence e notification -> no, non serve perchè li ha già crossServer

package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerMain {

    private static final int DEFAULT_PORT = 8080;
    private static final int THREAD_POOL_SIZE = 10;
    
    public static void main(String[] args) {
        int port = readPortFromConfig();
        
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("=== CROSS Server Avviato ===");
            System.out.println("Porta: " + port);
            System.out.println("Thread pool size: " + THREAD_POOL_SIZE);
            
            ExecutorService threadPool = 
            Executors.newFixedThreadPool(THREAD_POOL_SIZE);
            CROSSServer server = new CROSSServer();

            
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Nuovo client connesso: " + 
                clientSocket.getInetAddress());
                
                threadPool.execute(new ClientHandler(clientSocket, server));

            }
            
        } catch (IOException e) {
            System.err.println("Errore nel server: " + e.getMessage());
        }
    }
    
    private static int readPortFromConfig() {
        // Implementa lettura da file config_server.txt
        return DEFAULT_PORT;
    }
}