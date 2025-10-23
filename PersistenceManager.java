


package server;

import shared.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PersistenceManager {
    private final UserManager userManager;
    private final TradeManager tradeManager;
    private final OrderBook orderBook;
    private volatile boolean running = true;
    private Thread persistenceThread;
    
    // Cache dati giornalieri IN MEMORIA
    private final Map<String, DailyStats> dailyStatsCache = new ConcurrentHashMap<>();
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
    private final SimpleDateFormat monthFormat = new SimpleDateFormat("MMyyyy");
    
    // Controllo cambio giorno
    private String currentDay;
    private Timer dayChangeTimer;
    
    public PersistenceManager(UserManager userManager, TradeManager tradeManager, OrderBook orderBook) {
        this.userManager = userManager;
        this.orderBook = orderBook;
        this.currentDay = getCurrentDay();
        setupDayChangeChecker();
        loadDailyStatsCache(); // Carica dati storici
    }
    
    /**
     * Configura timer per controllo cambio giorno
     */
    private void setupDayChangeChecker() {
        dayChangeTimer = new Timer("DayChangeChecker", true);
        
        // Controlla ogni minuto se è cambiato il giorno
        dayChangeTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkDayChange(); // controlla se giorno è cambiato, salva stats precedenti se sì
            }
        }, 0, 60 * 1000); // Ogni minuto
    }
    
    /**
     * Controlla se è cambiato il giorno e salva le stats del giorno precedente
     */
    private void checkDayChange() {
        String today = getCurrentDay();
        
        if (!today.equals(currentDay)) {
            System.out.println("CAMBIO GIORNO: " + currentDay + " → " + today);
            
            // SALVA le stats del giorno PRECEDENTE
            DailyStats currentStats = dailyStatsCache.get(currentDay);
            if (currentStats != null) 
            {
                savePreviousDayStats(currentDay, currentStats);
                userManager.saveUsersToFile(); // Salva utenti al cambio giorno
            }
            else 
            {
                System.out.println("Nessuna statistica da salvare per " + currentDay);
            }

            // Prepara nuova cache per il giorno corrente
            currentDay = today;
            DailyStats newcurrentStats = new DailyStats();
            // metto il prezzo di apertura a 0, verrà settato al primo trade
            newcurrentStats.openPrice = 0;

            dailyStatsCache.put(currentDay, newcurrentStats);

            
            System.out.println("Nuovo giorno inizializzato: " + today);
        }
    }



    /**
     * Salva le statistiche del giorno precedente su file
     */

    private void savePreviousDayStats(String previousDay) { // forse ci aggiungo il file come parametro (?)
        DailyStats previousDayStats = dailyStatsCache.get(previousDay);
        if (previousDayStats == null || previousDayStats.totalTrades == 0) {
            System.out.println("Giorno " + previousDay + ": nessun trade, salvataggio skipped");
            return;
        }
        
        try {
            // APPEND della riga con le stats del giorno
            JSONObject dayStats = new JSONObject();
            dayStats.put("date", previousDay);
            dayStats.put("open", previousDayStats.openPrice);
            dayStats.put("close", previousDayStats.closePrice);
            dayStats.put("high", previousDayStats.highPrice);
            dayStats.put("low", previousDayStats.lowPrice);
            dayStats.put("totalTrades", previousDayStats.totalTrades);
            dayStats.put("totalVolume", previousDayStats.totalVolume);
            
            String logLine = dayStats.toString() + "\n";
            
            Files.write(Paths.get(DAILY_STATS_FILE), 
                       logLine.getBytes(),
                       StandardOpenOption.CREATE,  // crea se non esiste, controlla se è corretto (?)
                       StandardOpenOption.APPEND);
            
            System.out.println("Stats giorno " + previousDay + " salvate: " + 
                             previousDayStats.totalTrades + " trades");
            
        } catch (Exception e) {
            System.err.println("Errore salvataggio stats " + previousDay + ": " + e.getMessage());
        }
    }
    
    
    /**
     * Carica tutte le daily stats dal file, serve per richieste storiche
     */
    private JSONObject loadAllDailyStats() {
        try {
            File file = new File("data/daily_stats.json"); // metti file giusto (?)
            if (file.exists()) {
                String content = new String(Files.readAllBytes(file.toPath()));
                return new JSONObject(content);
            }
        } catch (Exception e) {
            System.err.println("Errore caricamento daily stats: " + e.getMessage());
        }
        return new JSONObject(); // Nuovo file vuoto
        //(?)
        // carico in memoria tutte le stats in una mappa
        // e poi faccio il filtro per mese quando serve
        

    }
    
    /**
     * Aggiornamento IMMEDIATO cache quando esegui un trade
     */
    public void persistTrades(Trade trades) {
        try {

            //scorro i trade eseguiti
            for (Trade trade : trades) {
                // 1. Append IMMEDIATO trade al log
                appendTradeToLogFile(trade);
            
                // 2. Aggiornamento cache giornaliera IN MEMORIA
                updateDailyStatsCache(trade);
            
            System.out.println("Trade salvato: " + trade.getTradeId());
            }

        } catch (Exception e) {
            System.err.println("Errore salvataggio trade: " + e.getMessage());
        }
    }
    
    /**
     * Aggiorna cache in memoria (NON salva su file)
     */
    private void updateDailyStatsCache(Trade trade) {
        String dayKey = dayFormat.format(new Date(trade.getTimestamp()));
        DailyStats dailyStats = dailyStatsCache.computeIfAbsent(dayKey, k -> new DailyStats());
        
        // Aggiorna statistiche in memoria
        dailyStats.totalTrades++;
        dailyStats.totalVolume += trade.getSize();
        
        // Prezzo di apertura (primo trade del giorno)
        if (dailyStats.openPrice == 0) {
            dailyStats.openPrice = trade.getPrice();
            System.out.println("Prezzo apertura per " + dayKey + ": " + trade.getPrice());
        }
        
        // Prezzo di chiusura (sempre l'ultimo trade)
        dailyStats.closePrice = trade.getPrice();
        
        // Prezzi massimo e minimo
        if (trade.getPrice() > dailyStats.highPrice || dailyStats.highPrice == 0) {
            dailyStats.highPrice = trade.getPrice();
        }
        if (trade.getPrice() < dailyStats.lowPrice || dailyStats.lowPrice == 0) {
            dailyStats.lowPrice = trade.getPrice();
        }
        

        }
    }
    
    /**
     * Append IMMEDIATO di un trade al log file
     */
    private void appendTradeToLogFile(Trade trade) throws IOException {
        JSONObject tradeJson = tradeToJSON(trade);
        String logLine = tradeJson.toString() + "\n";
        
        Files.write(Paths.get("data/trades_log.jsonl"), // controlla il path (?)
                   logLine.getBytes(),
                   StandardOpenOption.CREATE, // (?) riguarda e capisci funzionamento
                   StandardOpenOption.APPEND);
    }
    
    // === GESTIONE RICHIESTE DATI STORICI ===
    
    /**
     * Ricerca dati mensili - Ora è IMMEDIATA
     */
    public JSONObject getPriceHistory(String month) {
        if (!isValidMonthFormat(month)) {
            return createErrorResponse("Formato mese non valido. Usa MMyyyy (es. 012024)");
        }
        
        try {
            // Carica tutte le daily stats
            JSONObject allStats = loadAllDailyStats();
            JSONArray daysArray = new JSONArray();
            int totalTrades = 0;
            int totalVolume = 0;
            
            // Filtra per mese richiesto
            for (String dayKey : allStats.keySet()) {
                if (dayKey.startsWith(month.substring(2, 6) + "-" + month.substring(0, 2))) {
                    JSONObject dayStats = allStats.getJSONObject(dayKey);
                    daysArray.put(dayStats);
                    totalTrades += dayStats.getInt("totalTrades");
                    totalVolume += dayStats.getInt("totalVolume");
                }
            }
            
            JSONObject result = new JSONObject();
            result.put("month", month);
            result.put("status", "SUCCESS");
            result.put("totalTrades", totalTrades);
            result.put("totalVolume", totalVolume);
            result.put("days", daysArray);
            result.put("source", "PRE_CALCULATED"); // Dati precalcolati!
            
            System.out.println("Dati storici per " + month + ": " + daysArray.length() + " giorni");
            return result;
            
        } catch (Exception e) {
            System.err.println("Errore richiesta dati " + month + ": " + e.getMessage());
            return createErrorResponse("Errore nel recupero dati storici");
        }
    }
    
    // === METODI DI SUPPORTO ===
    
    private String getCurrentDay() {
        return dayFormat.format(new Date());
    }
    
    private boolean isValidMonthFormat(String month) {
        return month != null && month.matches("\\d{6}") && month.length() == 6;
    }
    
    /**
     * Carica cache all'avvio (solo dati giorno corrente)
     */
    private void loadDailyStatsCache() {
        // Inizializza solo il giorno corrente
        dailyStatsCache.put(currentDay, new DailyStats());
        // metto il prezzo di apertura a 0, verrà settato al primo trade
        dailyStatsCache.get(currentDay).openPrice = 0;
        System.out.println("Cache giornaliera inizializzata per: " + currentDay);
    }
    
    /**
     * Ferma il manager e salva le stats finali
     */
    public void stop() {
        running = false;
        
        if (dayChangeTimer != null) {
            dayChangeTimer.cancel();
        }
        
        // SALVATAGGIO FINALE del giorno corrente
        savePreviousDayStats(currentDay);
        
        System.out.println("PersistenceManager fermato - Stats salvate per: " + currentDay);
    }
    
    // === CLASSI DI SUPPORTO ===
    
    private static class DailyStats {
        int openPrice = 0;
        int closePrice = 0;
        int highPrice = 0;
        int lowPrice = 0;
        int totalTrades = 0;
        int totalVolume = 0;
        long firstTradeTime = 0;
        long lastTradeTime = 0;
    }
    
    private JSONObject dailyStatsToJSON(DailyStats stats) {
        JSONObject json = new JSONObject();
        json.put("open", stats.openPrice);
        json.put("close", stats.closePrice);
        json.put("high", stats.highPrice);
        json.put("low", stats.lowPrice);
        json.put("totalTrades", stats.totalTrades);
        json.put("totalVolume", stats.totalVolume);
        json.put("firstTradeTime", stats.firstTradeTime);
        json.put("lastTradeTime", stats.lastTradeTime);
        return json;
    }
    
    private DailyStats jsonToDailyStats(JSONObject json) {
        DailyStats stats = new DailyStats();
        stats.openPrice = json.getInt("open");
        stats.closePrice = json.getInt("close");
        stats.highPrice = json.getInt("high");
        stats.lowPrice = json.getInt("low");
        stats.totalTrades = json.getInt("totalTrades");
        stats.totalVolume = json.getInt("totalVolume");
        stats.firstTradeTime = json.getLong("firstTradeTime");
        stats.lastTradeTime = json.getLong("lastTradeTime");
        return stats;
    }
    
    private JSONObject tradeToJSON(Trade trade) {
        JSONObject json = new JSONObject();
        json.put("tradeId", trade.getTradeId());
        json.put("timestamp", trade.getTimestamp());
        json.put("buyer", trade.getBuyer());
        json.put("seller", trade.getSeller());
        json.put("size", trade.getSize());
        json.put("price", trade.getPrice());
        json.put("bidOrderId", trade.getBidOrderId());
        json.put("askOrderId", trade.getAskOrderId());
        json.put("side", trade.getSide().toString());
        return json;
    }
    
    private JSONObject createErrorResponse(String message) {
        JSONObject error = new JSONObject();
        error.put("status", "ERROR");
        error.put("message", message);
        error.put("timestamp", System.currentTimeMillis());
        return error;
    }



    /**
 * Logga un aggiornamento di un ordine (esaurito o parzialmente eseguito)
 */
public void logOrderUpdate(Order order) {
    try {
        JSONObject orderLog = new JSONObject();
        orderLog.put("timestamp", System.currentTimeMillis());
        orderLog.put("orderId", order.getOrderId());
        orderLog.put("username", order.getUsername());
        orderLog.put("side", order.getSide().toString()); // BID o ASK
        orderLog.put("type", order.getType().toString()); // LIMIT, STOP, MARKET
        orderLog.put("originalSize", order.Size());
        orderLog.put("remainingSize", order.getRemainingSize());
        
        // Determina lo status
        if (order.isLimitOrder()) {
            orderLog.put("status", (order.remainingSize()==0) ? "FILLED" : "PARTIALLY_FILLED");
            orderLog.put("limitPrice", order.getLimitPrice());
        }
        
        if (order.isStopOrder()) {
            orderLog.put("stopPrice", order.getStopPrice());
        }
        
        String logLine = orderLog.toString() + "\n";
        
        Files.write(Paths.get("data/order_updates.jsonl"),
                   logLine.getBytes(),
                   StandardOpenOption.CREATE,
                   StandardOpenOption.APPEND); // controlla path giusto
        
    } catch (Exception e) {
        System.err.println("Errore nel log order update: " + e.getMessage());
    }
}


    /**
     * Logga la cancellazione di un ordine
     */
    public void logOrderCancellation(Order order, String cancelledBy) {
        try {
            JSONObject cancelLog = new JSONObject();
            cancelLog.put("timestamp", System.currentTimeMillis());
            cancelLog.put("action", "CANCELLED");
            cancelLog.put("orderId", order.getOrderId());
            cancelLog.put("username", order.getUsername());
            cancelLog.put("side", order.getSide().toString());
            cancelLog.put("type", order.getType().toString());
            cancelLog.put("Size", order.getSize());
            cancelLog.put("remainingSize", order.getRemainingSize());
            
            if (order.isLimitOrder()) {
                cancelLog.put("limitPrice", order.getLimitPrice());
            }
            
            if (order.isStopOrder()) {
                cancelLog.put("stopPrice", ((StopOrder) order).getStopPrice());
            }
            
            String logLine = cancelLog.toString() + "\n";
            
            Files.write(Paths.get("data/order_updates.jsonl"),
                    logLine.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            

            
        } catch (Exception e) {
            System.err.println("Errore nel log order cancellation: " + e.getMessage());
        }
    }

}