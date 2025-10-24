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