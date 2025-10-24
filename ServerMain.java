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

    private static final int PORT;
    private static final int THREAD_POOL_SIZE;
    private static final String CONFIG_FILE = "config_server.txt";
    
    public static void main(String[] args) {
        PORT = readPortFromConfig();
        THREAD_POOL_SIZE = readThreadPoolSizeFromConfig();
        
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("=== CROSS Server Avviato ===");
            System.out.println("Porta: " + PORT);
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
        try {
            String value = readConfigValue("server_port");
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (Exception e) {
            System.err.println("Errore lettura server_port da config: " + e.getMessage());
        }
    }

    private static int readThreadPoolSizeFromConfig() {
        try {
            String value = readConfigValue("thread_pool_size");
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (Exception e) {
            System.err.println("Errore lettura thread_pool_size da config: " + e.getMessage());
        }
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
        
        System.err.println("Chiave '" + key + "' non trovata nel config. Usando valore default.");
        
    } catch (Exception e) {
        System.err.println("Errore lettura file config '" + CONFIG_FILE + "': " + e.getMessage() + ". Usando valori default.");
    }
    
    return null;
}
}
