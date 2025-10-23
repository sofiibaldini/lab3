
/*
 * CROSSServer.java
 * cosa fa questa classe:
 * - gestisce le funzionalità principali del server CROSS   
 * - mantiene istanze di UserManager, OrderBook, TradeManager e NotificationService
 * - fornisce metodi per la registrazione, login, logout degli utenti, chiamando il servizio e handler giusto 
 */

 //gestisce numerazione ordini, chiama inactivity timer, tiene riferimenti a orderbook e notificationservice, userManager
 // guarda anche se deve tenere notificationservice

package server;

import shared.*;
import org.json.JSONObject;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class CROSSServer {
    private UserManager userManager;
    private OrderBook orderBook;
    private PersistenceManager persistenceManager;
    private NotificationService notificationService;
    private AtomicLong orderIdGenerator;
    private Timer periodicTimer;
    
    public CROSSServer() {
        this.userManager = new UserManager("data/users.json", persistenceManager, notificationService);
        this.orderBook = new OrderBook(persistenceManager, notificationService);
        this.orderIdGenerator = new AtomicLong(1000);
        
        // startPeriodicChecks(); non penso serva

        System.out.println("CROSSServer inizializzato");
    }
    
    // === GESTIONE UTENTI ===  chiama i vari metodi su usermanager
	    public int registerUser(String username, String password) {
	        return userManager.registerUser(username, password);
	    }
	    public int loginUser(String username, String password, InetAddress address) {
	        return userManager.loginUser(username, password, address);
	    }
	    public int logoutUser(String username) {
	        return userManager.logoutUser(username);
	    }
        public int updateCredentials(String username, String currentPassword, String newPassword) {
            return userManager.updateCredentials(username, currentPassword, newPassword);
        }

    
    // ========================= GESTIONE ORDINI ===============
    
    
    public long insertLimitOrder(String username, OrderSide side,
     int size, int price) {
        synchronized (orderBook) {
            userManager.startInactivityTimer(username);
            // numero ordine
            long orderId = orderIdGenerator.getAndIncrement();
            // creo ordine
            LimitOrder limitOrder = new LimitOrder(orderId, username, side, size, price);
            
            // lo aggiungo ai limitorder
            boolean added = orderBook.addLimitOrder(limitOrder);
            
            if (added) {
                // Esegui matching immediato dei limit order (non penso serva (?))
                orderBook.matchLimitOrders();
                return orderId;
            } else {
                return -1;
            }
        }
    }
    
    public long insertMarketOrder(String username, OrderSide side, int size) {
        synchronized (orderBook) {
            userManager.startInactivityTimer(username);
            long orderId = orderIdGenerator.getAndIncrement();
            MarketOrder marketOrder = new MarketOrder(orderId, username, side, size);
            boolean executed = orderBook.executeMarketOrder(marketOrder);
            return executed ? orderId : -1;
        }
    }
    
    public long insertStopOrder(String username, OrderSide side, int size, int stopPrice) {
        synchronized (orderBook) {
            userManager.startInactivityTimer(username);
            long orderId = orderIdGenerator.getAndIncrement();
            StopOrder stopOrder = new StopOrder(orderId, username, 
            side, size, stopPrice);
            
            orderBook.addStopOrder(stopOrder);
            return orderId;
        }
    }
    
    public boolean cancelOrder(String username, long orderId) {
        synchronized (orderBook) {
            userManager.startInactivityTimer(username);
            return orderBook.cancelOrder(orderId, username);
        }
    }
    
    
    
}