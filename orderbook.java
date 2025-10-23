// implementa getbyorderid

package server;

import shared.*; // riguarda (?)
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.stream.Collectors;
import java.io.IOException;
import java.net.Socket; // per UserSession
import java.util.Timer; // per i timer
import java.util.TimerTask; // per i timer

// ASK = vendere
// BID = comprare

public class OrderBook 
{
    // BID orders: prezzi decrescenti (migliori offerte prime)
    private Map<Integer, List<LimitOrder>> bidOrders;
    // ASK orders: prezzi crescenti (migliori richieste prime)  
    private Map<Integer, List<LimitOrder>> askOrders;
    
    // STOP orders
    private Map<Integer, List<StopOrder>> askStopOrders;
    private Map<Integer, List<StopOrder>> bidStopOrders;
    
    private int prezzoMercato; // (best bid + best ask)/2
    private PersistenceManager persistenceManager;
    private NotificationService notificationService;
    
    public OrderBook(PersistenceManager persistenceManager, NotificationService notificationService) {
        this.bidOrders = new TreeMap<>(Collections.reverseOrder()); 
        // Prezzi più alti prima
        this.askOrders = new TreeMap<>();                           
        // Prezzi più bassi prima
        this.bidStopOrders = new TreeMap<>();
        this.askStopOrders = new TreeMap<>();
        this.persistenceManager = persistenceManager;
        this.notificationService = notificationService;
    }


// ============================================== METODI DI ACCESSO ==========================

        // getters and setters prezzo mercato
    private int getPrezzoMercato() 
    { return prezzoMercato; }
    private void setPrezzoMercato(int prezzoMercato) 
    { this.prezzoMercato = prezzoMercato; }

    private LimitOrder getBestBidOrder() {
        if (bidOrders.isEmpty()) return null;
        
        Map.Entry<Integer, List<LimitOrder>> bestBidEntry = bidOrders.entrySet().iterator().next();
        List<LimitOrder> orders = bestBidEntry.getValue();
        
        return orders.isEmpty() ? null : orders.get(0); // Time priority
    }
    
    private LimitOrder getBestAskOrder() {
        if (askOrders.isEmpty()) return null;
        
        Map.Entry<Integer, List<LimitOrder>> bestAskEntry = askOrders.entrySet().iterator().next();
        List<LimitOrder> orders = bestAskEntry.getValue();
        
        return orders.isEmpty() ? null : orders.get(0); // Time priority
    }
    
    private Integer getBestBidPrice() {
        return bidOrders.isEmpty() ? null : bidOrders.keySet().iterator().next();
    }
    private Integer getBestAskPrice() {
        return askOrders.isEmpty() ? null : askOrders.keySet().iterator().next();
    }
    
    private int getBidAskSpread() {
        synchronized (this) {
            Integer bestBid = getBestBidPrice();
            Integer bestAsk = getBestAskPrice();
            
            if (bestBid == null || bestAsk == null) {
                return -1; // Non definito
            }
            
            return bestAsk - bestBid;
        }
    }

// ======================= AGGIUNGI ORDINI STOP E LIMIT ======================
    
    public boolean addLimitOrder(LimitOrder order) {
        synchronized (this) {
            Map<Integer, List<LimitOrder>> targetMap = (order.getSide() == OrderSide.BID) ? bidOrders : askOrders;
            
            int price = order.getLimitPrice();
            
            if (!targetMap.containsKey(price)) { 
            // se non ho ancora ordini di questo prezzo, creo aray vuoto
                targetMap.put(price, new ArrayList<>());
            }
            
            targetMap.get(price).add(order);

            // faccio partire algo matching
            matchLimitOrders();
            
            return true;
        }
    }
    
    public boolean addStopOrder(StopOrder order) {
        synchronized (this) {
            Map<Integer, List<StopOrder>> targetMap = (order.getSide() == OrderSide.BID) ? bidStopOrders : askStopOrders;

            int price = order.getLimitPrice();
            
            if (!targetMap.containsKey(price)) { 
            // se non ho ancora ordini di questo prezzo, creo aray vuoto
                targetMap.put(price, new ArrayList<>());
            }
            
            targetMap.get(price).add(order);


            // controllo se l'ordine è eseguibile (?)
            if (order.getSide() == OrderSide.BID) checkBidStopOrders(getPrezzoMercato());
            else checkAskStopOrders(getPrezzoMercato());
        
            return true;
        }
    }
      
// ========================== MARKET ORDER EXECUTION ===========================
    
    public boolean executeMarketOrder(MarketOrder marketOrder) {
        synchronized (this) {
            boolean executed = false;
            
            if (marketOrder.getSide() == OrderSide.BID) {
                executed = matchBidOrder (marketOrder);
            }
            else {
                executed = matchAskOrder (marketOrder);
            }
            return executed;
        }
    }
    
// ==================================== MATCHING ALGORITHM BID per market/stop attivato ===================

    public boolean matchBidOrder(Order newBid)  // lo chiamo per marketorder o stoporder attivati
    {
        List<Trade> trades = new ArrayList<>(); // lista degli scambi fatti
        List<LimitOrder> activatedLimitOrders = new ArrayList<>();
        
        // Cerco solo negli ASK orders (vendite)
        for (Map.Entry<Integer, List<LimitOrder>> askEntry : askOrders.entrySet()) 
        {
            int askPrice = askEntry.getKey(); // questo è il prezzo migliore
            List<LimitOrder> askOrdersAtPrice = askEntry.getValue(); //ordini al prezzo migliore
            
            // Scansiona tutti gli ASK a questo prezzo
            Iterator<LimitOrder> iterator = askOrdersAtPrice.iterator();
            while (iterator.hasNext() && newBid.getRemainingSize() > 0) 
            {
                Order askOrder = iterator.next(); // ask attuale
                int tradeSize = Math.min(newBid.getRemainingSize(), askOrder.getRemainingSize());  // quanto effettivamente prendo dell'ordine
                
                // Crea il trade
                Trade trade = new Trade(askOrder.getOrderId(), newBid.getOrderId(), tradeSize, askPrice, askOrder.getUsername(), newBid.getUsername());
                trades.add(trade); 
                
                newBid.setRemainingSize(newBid.getRemainingSize() - tradeSize); // aggiorno taglia ordine
                askOrder.setTempRemainingSize(askOrder.getRemainingSize() - tradeSize); // e ask (temporaneamente)
                // aggiungo l'ask a una lista di ordini attivati, da finalizzare sse il market/stop ha successo
                activatedLimitOrders.add((LimitOrder) askOrder);
            }
            if (newBid.getRemainingSize() <= 0) break; // Ordine completamente eseguito
        }

        if (newBid.getRemainingSize() > 0) 
        {
            return false;
        }
        // setto l'ordine come eseguito
        newBid.setExecuted(true);
        // setto gli ask come eseguiti del tutto o in parte
        for (Order order : activatedLimitOrders)
        {
            order.setRemainingSize(order.getTempRemainingSize()); // aggiorno la size reale dell'order
            // loggo l'ordine
            persistenceManager.logOrderUpdate(order);
            if (order.getRemainingSize() == 0)
            {
                order.setExecuted(true);
                // rimuovo l'ordine dall'orderbook
                persistenceManager.removeOrderFromBook(order);
            }
        }
        
        // loggo
        persistenceManager.logOrderUpdate(newBid);
        processTrades(trades);
        // eseguo il matching per controllare se ora posso eseguire qualche limit order
        matchLimitOrders();

        return true;
    }

// ==================================== MATCHING ALGORITHM ASK per market/stop attivato ===================
    public boolean matchAskOrder(Order newAsk)  // lo chiamo per marketorder o stoporder attivati
    {
        List<Trade> trades = new ArrayList<>(); // lista degli scambi fatti
        List<LimitOrder> activatedLimitOrders = new ArrayList<>();
        // int remainingSize = newAsk.getRemainingSize();
        
        // Cerco solo nei BID orders (acquisti)
        for (Map.Entry<Integer, List<LimitOrder>> bidEntry : bidOrders.entrySet()) 
        {
            int bidPrice = bidEntry.getKey(); // questo è il prezzo migliore
            List<LimitOrder> bidOrdersAtPrice = bidEntry.getValue(); //ordini al prezzo migliore
            
            // Scansiona tutti i BID a questo prezzo
                Iterator<LimitOrder> iterator = bidOrdersAtPrice.iterator();
                while (iterator.hasNext() && newAsk.getRemainingSize() > 0) 
                {
                    Order bidOrder = iterator.next(); // bid attuale
                    int tradeSize = Math.min(newAsk.getRemainingSize(), bidOrder.getRemainingSize());  // quanto effettivamente prendo dell'ordine
                    
                    // Crea il trade
                    Trade trade = new Trade(newAsk.getOrderId(), bidOrder.getOrderId(),tradeSize, bidPrice, newAsk.getUsername(), bidOrder.getUsername());
                    trades.add(trade); 
                    
                    newAsk.setRemainingSize(newAsk.getRemainingSize()-tradeSize); // aggiorno taglia ordine - qua non importa usare temp, se ordine fallisce non ha effetti
                    bidOrder.setTempRemainingSize(bidOrder.getRemainingSize()- tradeSize); // e bid (temporaneamente)
                    // aggiungo il bid a una lista di ordini attivati, da cambiare sse il market/stop ha successo
                    activatedLimitOrders.add(bidOrder);
                    
                }
                if (newAsk.getRemainingSize() <= 0) break; // Ordine completamente eseguito
        }

        if (newAsk.getRemainingSize() > 0) 
        {
            return false;
        }
        // setto l'ordine come eseguito
        newAsk.setExecuted(true);
        // setto i bid come eseguiti del tutto o in parte
        for (Order order : activatedLimitOrders) 
        {
            order.setRemainingSize(order.getTempRemainingSize()); // aggiorno la size reale dell'order
            // loggo l'ordine
            persistenceManager.logOrderUpdate(order);
            if (order.getRemainingSize()==0)
            {
                order.setExecuted(true);
                // rimuovo l'ordine dall'orderbook
                persistenceManager.removeOrderFromBook(order);
            }
        }
        
        // loggo
        persistenceManager.logOrderUpdate(newAsk);
        processTrades(trades);
        // eseguo il matching per controllare se ora posso eseguire qualche limit order
        matchLimitOrders();

        return true;
    }
  
// ================================== PROCESS TRADES ==========================

    /**
     * Processa una lista di trade eseguiti e gestisce tutti gli effetti a cascata
     */
    public void processTrades(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        
        try {
            
            // ===  AGGIORNAMENTO PREZZO MERCATO (controlla automaticamente stop orders) ===
            updatePrezzoMercato();

            // === NOTIFICHE AI CLIENT ===
            sendTradeNotifications(trades);
            
            // === PERSISTENZA DATI ===
            persistenceManager.persistTrades(trades);
            
        } catch (Exception e) {
            System.err.println("ERRORE nel processing trades: "
             + e.getMessage());
        }
    }


// ========================================= NOTIFICHE AI CLIENT ==============
    private void sendTradeNotifications(List<Trade> trades) {
        for (Trade trade : trades) {
            notificationService.notifyBuyer(trade);
            notificationService.notifySeller(trade);
            
        }
    }
  
    

// ======================== UPDATE PREZZO MERCATO E CHIAMATA CHECK STOP ORDERS ==========
    private void updatePrezzoMercato() {
        Integer bestBid = getBestBidPrice();
        Integer bestAsk = getBestAskPrice();
        Integer oldPrezzoMercato = getPrezzoMercato();
        if (bestBid == null || bestAsk == null) {
            // Non posso aggiornare il prezzo di mercato
            return;
        }
        setPrezzoMercato((getBestBidPrice() + getBestAskPrice()) / 2);
        if (bestBid != null && bestAsk != null) {
            if (getPrezzoMercato() < oldPrezzoMercato) {
                checkBidStopOrders(getPrezzoMercato());
            } else if (getPrezzoMercato() > oldPrezzoMercato) {
                checkAskStopOrders(getPrezzoMercato());
            }
        }
    }
    

// ================================== CHECKS SU STOP ORDERS quando cambia prezzo di mercato ================


    // da chiamare se prezzo di mercato diminuisce
       private void checkBidStopOrders(int currentPrice) {
        
        Iterator<Map.Entry<Integer, List<StopOrder>>> iterator = 
            bidStopOrders.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<Integer, List<StopOrder>> entry = iterator.next();
            int stopPrice = entry.getKey();
            
            if (stopPrice <= currentPrice) {         // Con TreeMap crescente, itero finchè stopPrice <= currentPrice
                // Tutti gli stop orders a questa soglia possono attivarsi
                List<StopOrder> ordersAtPrice = entry.getValue();
                Iterator<StopOrder> orderIterator = ordersAtPrice.iterator();
                
                while (orderIterator.hasNext()) {
                    StopOrder stopOrder = orderIterator.next();
                    if (!stopOrder.isExecuted()) {
                        int result =  matchBidOrder(stopOrder);
                    }
                }
                
                // Se la lista a questo prezzo è vuota, 
                // rimuovi la voce da TreeMap
                if (ordersAtPrice.isEmpty()) {
                    iterator.remove();
                }
                
            } else {
                // Map ordinata -> tutti succ hanno stopPrice > currentPrice
                break;
            }
        }
    }
    // da chiamare se prezzo di mercato aumenta
    private void checkAskStopOrders(int currentPrice) {

        Iterator<Map.Entry<Integer, List<StopOrder>>> iterator = askStopOrders.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<Integer, List<StopOrder>> entry = iterator.next();
            int stopPrice = entry.getKey();
            
            if (stopPrice >= currentPrice) {         // itero fino a che stopPrice >= currentPrice
                // Tutti gli stop orders a questa soglia possono attivarsi
                List<StopOrder> ordersAtPrice = entry.getValue();
                Iterator<StopOrder> orderIterator = ordersAtPrice.iterator();
                
                while (orderIterator.hasNext()) {
                    StopOrder stopOrder = orderIterator.next();
                    if (!stopOrder.isExecuted()) {
                        boolean done = matchAskOrder(stopOrder);
                    }
                }
                
                // Se la lista a questo prezzo è vuota, 
                // rimuovi la voce da TreeMap
                if (ordersAtPrice.isEmpty()) {
                    iterator.remove();
                }
                
            } else {
				// Map ordinata -> tutti succ hanno stopPrice < currentPrice
                break;
            }
        }
    }

//--------------------------------------- RIMOZIONE DI UN ORDINE GENERICO ----------------------
//                          non la versione chiamata dall'utente, toglie gli ordini eseguiti
    private void removeOrderFromBook(Order order) {
        synchronized (this) {
            if (order.isStopOrder())
            {
                // prendo la mappa di bid o ask
                Map<Integer, List<StopOrder>> targetMap = (order.getSide() == OrderSide.BID) ? bidStopOrders : askStopOrders;
            } 
            else if (order.isLimitOrder()) 
            {
               Map<Integer, List<LimitOrder>> targetMap = (order.getSide() == OrderSide.BID) ? bidOrders : askOrders;
            }


            if (order.isStopOrder())
            {
                StopOrder stopOrder = (StopOrder) order;
                int price = stopOrder.getStopPrice();
                List<StopOrder> ordersAtPrice = targetMap.get(price);
                if (ordersAtPrice != null) 
                {
                    // scorro la mappa con iterator
                    Iterator<StopOrder> iterator = ordersAtPrice.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().getOrderId() == order.getOrderId()) {
                            iterator.remove();
                            break;
                        }
                    }
                    // se la lista a quel prezzo è vuota, rimuovo la voce dalla mappa
                    if (ordersAtPrice.isEmpty()) {
                        targetMap.remove(price);
                    }
                }
            }
            else if (order.isLimitOrder())
            {
                LimitOrder limitOrder = (LimitOrder) order;
                int price = limitOrder.getLimitPrice();
                List<LimitOrder> ordersAtPrice = targetMap.get(price);
                if (ordersAtPrice != null) 
                {
                    // scorro la mappa con iterator
                    Iterator<LimitOrder> iterator = ordersAtPrice.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().getOrderId() == order.getOrderId()) {
                            iterator.remove();
                            break;
                        }
                    }
                    // se la lista a quel prezzo è vuota, rimuovo la voce dalla mappa
                    if (ordersAtPrice.isEmpty()) {
                        targetMap.remove(price);
                    }
                }
            }

        }
    }


    
// ========================================== CANCELLAZIONE ORDINI ========================
    public int cancelOrder(long orderId, String username) {

        synchronized (this) {
            // Cerca nei BID orders
            for (List<LimitOrder> orders : bidOrders.values()) {
                Iterator<LimitOrder> iterator = orders.iterator();
                while (iterator.hasNext()) 
                {
                    LimitOrder order = iterator.next();
                    if (order.getOrderId() == orderId) {
                        if (order.getUsername().equals(username)) {
                            iterator.remove();
                            persistenceManager.logOrderCancellation(order, username);
                            // devo controllare se si attivano limitorder
                            matchLimitOrders();
                            return 100; // OK
                        } else {
                            return 101; // belongs to different user
                        }
                    }
                }
            }
            
            // Cerca negli ASK orders
            for (List<LimitOrder> orders : askOrders.values()) {
                Iterator<LimitOrder> iterator = orders.iterator();
                while (iterator.hasNext()) 
                {
                    LimitOrder order = iterator.next();
                    if (order.getOrderId() == orderId) {
                        if (order.getUsername().equals(username)) 
                        {
                            iterator.remove();
                            persistenceManager.logOrderCancellation(order, username);
                            // devo controllare se si attivano limitorder
                            matchLimitOrders();
                            return 100; // OK
                        } else {
                            return 101; // belongs to different user
                        }
                    }
                }
            }
            
            // Cerca negli STOP orders, divisi in ask e bid
            // Cerca nei BID stop orders
            for (List<StopOrder> orders : bidStopOrders.values()) {
                Iterator<StopOrder> iterator = orders.iterator();
                while (iterator.hasNext()) 
                {
                    StopOrder order = iterator.next();
                    if (order.getOrderId() == orderId) {
                        if (order.getUsername().equals(username)) 
                        {
                            iterator.remove();
                            persistenceManager.logOrderCancellation(order, username);
                            return 100; // OK
                        } else {
                            return 101; // belongs to different user
                        }
                    }
                }
            }
            
            // Cerca negli ASK stop orders
            for (List<StopOrder> orders : askStopOrders.values()) {
                Iterator<StopOrder> iterator = orders.iterator();
                while (iterator.hasNext()) 
                {
                    StopOrder order = iterator.next();
                    if (order.getOrderId() == orderId) {
                        if (order.getUsername().equals(username)) 
                        {
                            iterator.remove();
                            persistenceManager.logOrderCancellation(order, username);
                            return 100; // OK
                        } else {
                            return 101; // belongs to different user
                        }
                    }
                }
            }
            return 101; // order does not exist or has already been finalized
        }
    }
    


// =================================================================== MATCHING LIMIT ORDERS =========================
    public void matchLimitOrders() 
    {
        List<Trade> activatedTrades = new ArrayList<>();
        boolean foundMatch;
        
        do {
            foundMatch = false;
            
            // Prendi i MIGLIORI ordini da entrambi i lati
            LimitOrder bestBid = getBestBidOrder();
            LimitOrder bestAsk = getBestAskOrder();
            
            // Verifica se possono matchare
            if (bestBid != null && bestAsk != null && 
                bestBid.getLimitPrice() >= bestAsk.getLimitPrice()) {
                
                foundMatch = true;
                
                // Calcola quantità del trade
                int tradeSize = Math.min(bestBid.getRemainingSize(), bestAsk.getRemainingSize());
                int tradePrice = bestAsk.getLimitPrice(); // Prezzo del venditore
                
                // Crea il trade
                Trade trade = new Trade( bestAsk.getOrderId(), bestBid.getOrderId(), tradeSize, tradePrice, bestAsk.getUsername(), bestBid.getUsername());
                activatedTrades.add(trade);
                
                
                // Aggiorna le quantità
                bestBid.setRemainingSize(bestBid.getRemainingSize()-tradeSize); 
                bestAsk.setRemainingSize(bestAsk.getRemainingSize()-tradeSize);
                
                // Gestisci ordini completati
                if (bestBid.getRemainingSize()==0) { // bid completato, setto come eseguito e lo rimuovo - domanda: serve settare a eseguito (?)
                    bestBid.setExecuted(true);
                    persistenceManager.logOrderUpdate(bestBid);
                    removeOrderFromBook(bestBid);
                }
                if (bestAsk.getRemainingSize()==0) {
                    bestAsk.setExecuted(true);
                    persistenceManager.logOrderUpdate(bestAsk);
                    removeOrderFromBook(bestAsk);
                }

                // Se ordini non completati ma parzialmente eseguiti, aggiorna
                if (bestBid.getRemainingSize() != 0) {
                    persistenceManager.logOrderUpdate(bestBid);
                }
                if (bestAsk.getRemainingSize() != 0) {
                    persistenceManager.logOrderUpdate(bestAsk);
                }
            }
            
        } while (foundMatch);
        
        // Processa tutti i trades trovati
        if (!activatedTrades.isEmpty()) {
            processTrades(activatedTrades); // cambia prezzo mercato, chiama controllo stoporder se serve, invia notifiche, logga trades
        }

        return;
    }
}


