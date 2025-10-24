package client;

import java.util.Scanner;

public class ClientMain {
    private static CROSSClient client;
    private static Scanner scanner;
    
    public static void main(String[] args) {
        scanner = new Scanner(System.in);
        // oggetto che legge input da utente tramite console
        client = new CROSSClient();
        
        System.out.println("=== CROSS Client ===");
        
        boolean running = true;
        while (running) {
            if (!client.isLoggedIn()) {
                showPreLoginMenu();
            } else {
                showPostLoginMenu();
            }
            
            System.out.print("Scegli un'opzione: ");
            String choice = scanner.nextLine();
            
            running = processChoice(choice);
        }
        
        scanner.close();
        client.disconnect();
    }
    
    private static void showPreLoginMenu() {
        System.out.println("\n1. Registrati");
        System.out.println("2. Login");
        System.out.println("3. Esci");
    }
    
    private static void showPostLoginMenu() {
        System.out.println("\n=== Menu Principale ===");
        System.out.println("1. Inserisci Limit Order");
        System.out.println("2. Inserisci Market Order");
        System.out.println("3. Inserisci Stop Order");
        System.out.println("4. Cancella Ordine");
        System.out.println("5. Storico Prezzi");
        System.out.println("6. Logout");
    }
    
private static boolean processChoice(String choice) {
    if (!client.isLoggedIn()) {
        return processPreLoginChoice(choice);
    } else {
        return processPostLoginChoice(choice);
    }
}

private static boolean processPreLoginChoice(String choice) {
    switch (choice) {
        case "1":
            handleRegister();
            return true;
        case "2":
            handleLogin();
            return true;
        case "3":
            return false; // Esci
        default:
            System.out.println("Opzione non valida");
            return true;
    }
}

private static boolean processPostLoginChoice(String choice) {
    switch (choice) {
        case "1":
            handleInsertLimitOrder();
            return true;
        case "2":
            handleInsertMarketOrder();
            return true;
        case "3":
            handleInsertStopOrder();
            return true;
        case "4":
            handleCancelOrder();
            return true;
        case "5":
            handlePriceHistory();
            return true;
        case "6":
            handleLogout();
            return true;
        default:
            System.out.println("Opzione non valida");
            return true;
    }
}
    
    private static void handleRegister() {
        System.out.print("Username: ");
        String username = scanner.nextLine();
        System.out.print("Password: ");
        String password = scanner.nextLine();
        
        boolean success = client.register(username, password);
        if (success) {
            System.out.println("Registrazione completata!");
        } else {
            System.out.println("Errore nella registrazione");
        }
    }
    
    private static void handleLogin() {
        System.out.print("Username: ");
        String username = scanner.nextLine();
        System.out.print("Password: ");
        String password = scanner.nextLine();
        
        boolean success = client.login(username, password);
        if (success) {
            System.out.println("Login effettuato!");
        } else {
            System.out.println("Errore nel login");
        }
    }
    
    private static void handleInsertLimitOrder() {
        System.out.print("Tipo (bid/ask): ");
        String type = scanner.nextLine();
        System.out.print("Dimensione (in millesimi di BTC): ");
        int size = Integer.parseInt(scanner.nextLine());
        System.out.print("Prezzo Limite (in millesimi di USD): ");
        int price = Integer.parseInt(scanner.nextLine());
        
        long orderId = client.insertLimitOrder(type, size, price);
        if (orderId != -1) {
            System.out.println("Ordine inserito con ID: " + orderId);
        } else {
            System.out.println("Errore nell'inserimento dell'ordine");
        }
    }
    
    private static void handleLogout() {
        client.logout();
        System.out.println("Logout effettuato");
    }
    
    // Metodi simili per le altre operazioni...
}