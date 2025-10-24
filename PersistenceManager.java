


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
import java.nio.file.StandardOpenOption; 

public class PersistenceManager {
    private final UserManager userManager;
    private final OrderBook orderBook;
    private volatile boolean running = true;

    private final Object dailyStatsLock = new Object();
    private final Object tradesLock = new Object();
    private final Object orderUpdatesLock = new Object();
    
    // Cache dati giornalieri IN MEMORIA
    private final Map<String, DailyStats> dailyStatsCache = new ConcurrentHashMap<>();
    private static final String DAILY_STATS_FILE = "data/daily_stats.jsonl"; // da rivedere (?)
    private static final String TRADES_LOG_FILE = "data/trades_log.jsonl";
    private static final String ORDER_UPDATES_FILE = "data/order_updates.jsonl";

    private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
    private final SimpleDateFormat monthFormat = new SimpleDateFormat("MMyyyy");
    
    // Controllo cambio giorno
    private String currentDay;
    private Timer dayChangeTimer;
    
    public PersistenceManager(UserManager userManager, OrderBook orderBook) {
        this.userManager = userManager;
        this.orderBook = orderBook;
        this.currentDay = getCurrentDay();
        setupDayChangeChecker();

        dailyStatsCache.put(currentDay, new DailyStats()); // inizializza cache giorno corrente
        // metto il prezzo di apertura a 0, verrà settato al primo trade
        dailyStatsCache.get(currentDay).openPrice = 0;
        // metto prezzo max, min a 0
        dailyStatsCache.get(currentDay).highPrice = 0;
        dailyStatsCache.get(currentDay).lowPrice = 0;
    }

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

// -------------------------------------------------- Configura timer per controllo cambio giorno --------------------------
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
    
// -------------------------------------------------Controlla se è cambiato il giorno e salva le stats del giorno precedente --------------------------

    private void checkDayChange() {
        String today = getCurrentDay();
        
        if (!today.equals(currentDay)) {
            
            // SALVA le stats del giorno PRECEDENTE
            DailyStats currentStats = dailyStatsCache.get(currentDay);
            if (currentStats != null) 
            {
                savePreviousDayStats(currentDay);
                userManager.saveUsersToFile(); // Salva utenti al cambio giorno
            }

            // Prepara nuova cache per il giorno corrente
            currentDay = today;
            DailyStats newcurrentStats = new DailyStats();
            // metto il prezzo di apertura a 0, verrà settato al primo trade
            newcurrentStats.openPrice = 0;
            newcurrentStats.highPrice = 0;
            newcurrentStats.lowPrice = 0;

            dailyStatsCache.put(currentDay, newcurrentStats);

            
        }
    }



// ---------------------------------------------------- Salva le statistiche del giorno precedente su file --------------------------

    private void savePreviousDayStats(String previousDay) { // forse ci aggiungo il file come parametro (?)
        DailyStats previousDayStats = dailyStatsCache.get(previousDay);
        if (previousDayStats == null || previousDayStats.totalTrades == 0) {
            // salvo tutto a 0
            previousDayStats = new DailyStats();
            dailyStatsCache.put(previousDay, previousDayStats);
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

            synchronized (dailyStatsLock) {
                Files.write(Paths.get(DAILY_STATS_FILE),
                        logLine.getBytes(),
                        StandardOpenOption.CREATE,  // crea se non esiste, controlla se è corretto (?)
                        StandardOpenOption.APPEND);
            }
            
        } catch (Exception e) {
            System.err.println("Errore salvataggio stats " + previousDay + ": " + e.getMessage());
        }
    }
    
    

// ----------------------------------------------------Carica tutte le daily stats dal file, serve per richieste storiche

    private Map<String, DailyStats> loadAllDailyStats() 
    {
        Map<String, DailyStats> allStats = new TreeMap<>();
        try {
            synchronized (dailyStatsLock) {
                Path path = Paths.get(DAILY_STATS_FILE);
                if (Files.exists(path)) {
                    List<String> lines = Files.readAllLines(path);
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            try {
                                JSONObject json = new JSONObject(line);
                                String date = json.getString("date");
                                allStats.put(date, jsonToDailyStats(json));
                            } catch (Exception e) {
                                System.err.println("Riga JSONL non valida: " + line);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Errore caricamento daily stats: " + e.getMessage());
        }
        return allStats;
    }
    
// -------------------------------------------------- append dei trade, chiama aggiornamento cache --------------------------
    public void persistTrades( List<Trade> trades) {
        try {

            //scorro i trade eseguiti
            for (Trade trade : trades) {
                // 1. Append IMMEDIATO trade al log
                appendTradeToLogFile(trade);
            
                // 2. Aggiornamento cache giornaliera IN MEMORIA
                updateDailyStatsCache(trade);
            
            }

        } catch (Exception e) {
            System.err.println("Errore salvataggio trade: " + e.getMessage());
        }
    }
    
// -------------------------------------------------- Aggiorna cache (non file) delle stats giornaliere dopo un trade --------------------------
    private void updateDailyStatsCache(Trade trade) {
        String dayKey = dayFormat.format(new Date(trade.getTimestamp()));
        DailyStats dailyStats = dailyStatsCache.computeIfAbsent(dayKey, k -> new DailyStats()); // ritorna il riferimento esistente o ne crea uno nuovo
        
        // Aggiorna statistiche in memoria
        dailyStats.totalTrades++;
        dailyStats.totalVolume += trade.getSize();
        
        // Prezzo di apertura (primo trade del giorno)
        if (dailyStats.openPrice == 0) {
            dailyStats.openPrice = trade.getPrice();
            System.err.println("Prezzo apertura per " + dayKey + ": " + trade.getPrice());
        }
        
        // Prezzo di chiusura (sempre l'ultimo trade)
        dailyStats.closePrice = trade.getPrice();
        
        // Prezzi massimo e minimo
        if ((trade.getPrice() > dailyStats.highPrice) || dailyStats.highPrice == 0) {
            dailyStats.highPrice = trade.getPrice();
        }
        if ((trade.getPrice() < dailyStats.lowPrice) || dailyStats.lowPrice == 0) {
            dailyStats.lowPrice = trade.getPrice();
        }
        

    }
    
//------------------------------------------------------------- Append IMMEDIATO di un trade al log file

    private void appendTradeToLogFile(Trade trade) throws IOException {
        JSONObject tradeJson = tradeToJSON(trade);
        String logLine = tradeJson.toString() + "\n";

        synchronized (tradesLock) {
            Files.write(Paths.get(TRADES_LOG_FILE),
                    logLine.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
    }
    
// =================================================================== GESTIONE RICHIESTA DATI STORICI ==========================

    // Ricerca dati mensili - Ora è IMMEDIATA

    public JSONObject getPriceHistory(String month) { // riguardalo, più tiene totale e volume totale (?)
        if (!isValidMonthFormat(month)) {
            return createErrorResponse("Formato mese non valido. Usa MMyyyy (es. 012024)");
        }
        
        try {
            TreeMap<String, DailyStats> allStats = loadAllDailyStats();
            JSONArray daysArray = new JSONArray();
            int totalTrades = 0;
            int totalVolume = 0;
            
            // Calcola range di date per il mese richiesto
            String monthNum = month.substring(0, 2);
            String year = month.substring(2);
            String startDate = year + "-" + monthNum + "-01";
            String endDate = year + "-" + monthNum + "-31"; // TreeMap gestirà i giorni validi
            
            // Naviga solo nel range del mese
            SortedMap<String, DailyStats> monthStats = allStats.subMap(startDate, true, endDate, true);
            
            for (Map.Entry<String, DailyStats> entry : monthStats.entrySet()) {
                JSONObject dayStats = dailyStatsToJSON(entry.getValue());
                dayStats.put("date", entry.getKey());
                daysArray.put(dayStats);
                totalTrades += entry.getValue().totalTrades;
                totalVolume += entry.getValue().totalVolume;
            }
            
            JSONObject result = new JSONObject();
            result.put("month", month);
            result.put("status", "SUCCESS");
            result.put("days", daysArray); // array di oggetti giornalieri

            System.err.println("Dati per " + month + ": " + daysArray.length() + " giorni in ordine");
            return result;
            
        } catch (Exception e) {
            return createErrorResponse("Errore nel recupero dati storici");
        }
    }




// ==================================================================== METODI DI SUPPORTO ==========================

    private String getCurrentDay() {
        return dayFormat.format(new Date());
    }

    private boolean isValidMonthFormat(String month) {
        if (month == null || !month.matches("\\d{6}")) { // \\d qualsiasi cifra, {6} esattamente 6 volte
            return false;
        }
        
        try {
            int monthNum = Integer.parseInt(month.substring(0, 2));
            int year = Integer.parseInt(month.substring(2));
            
            // Valida mese (1-12) e anno (es: 2000-2100)
            return monthNum >= 1 && monthNum <= 12 && year >= 2000 && year <= 2100;
            
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    private JSONObject dailyStatsToJSON(DailyStats stats) { // trasforma DailyStats in JSONObject
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
    
    private DailyStats jsonToDailyStats(JSONObject json) { // trasforma JSONObject in DailyStats
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
    
    private JSONObject createErrorResponse(String message)  // riguarda se è corretto come lo vuole lui (?)
    {
        JSONObject error = new JSONObject();
        error.put("status", "ERROR");
        error.put("message", message);
        return error;
    }

    // Ferma il manager e salva le stats finali
    public void stop() {
        running = false;
        
        if (dayChangeTimer != null) {
            dayChangeTimer.cancel();
        }
        
        // SALVATAGGIO FINALE del giorno corrente
        savePreviousDayStats(currentDay);
        
        System.err.println("PersistenceManager fermato - Stats salvate per: " + currentDay);
    }



//------------------------------------------------- Logga un aggiornamento di un ordine (esaurito o parzialmente eseguito)

    public void logOrderUpdate(Order order) {
        try {
            JSONObject orderLog = new JSONObject();
            orderLog.put("timestamp", System.currentTimeMillis());
            orderLog.put("orderId", order.getOrderId());
            orderLog.put("username", order.getUsername());
            orderLog.put("side", order.getSide().toString()); // BID o ASK
            orderLog.put("type", order.getType().toString()); // LIMIT, STOP, MARKET
            orderLog.put("originalSize", order.getSize());
            orderLog.put("remainingSize", order.getRemainingSize());
            
            // Determina lo status
            if (order.isLimitOrder()) {
                orderLog.put("status", (order.getRemainingSize()==0) ? "FILLED" : "PARTIALLY_FILLED");
                orderLog.put("limitPrice", order.getLimitPrice());
            }
            
            if (order.isStopOrder()) {
                orderLog.put("stopPrice", order.getStopPrice());
            }
            
            String logLine = orderLog.toString() + "\n";
            
            synchronized (orderUpdatesLock) {
            Files.write(Paths.get(ORDER_UPDATES_FILE),
                    logLine.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND); // controlla path giusto
            }

        } catch (Exception e) {
            System.err.println("Errore nel log order update: " + e.getMessage());
        }
    }


// ------------------------------------------------------------------------------Logga la cancellazione di un ordine
    public void logOrderCancellation(Order order, String cancelledBy) {
        try {
            JSONObject cancelLog = new JSONObject();
            cancelLog.put("timestamp", System.currentTimeMillis());
            cancelLog.put("action", "CANCELLED");
            cancelLog.put("orderId", order.getOrderId());
            cancelLog.put("username", order.getUsername());
            cancelLog.put("side", order.getSide().toString());
            cancelLog.put("type", order.getType().toString());
            cancelLog.put("size", order.getSize());
            cancelLog.put("remainingSize", order.getRemainingSize());
            
            if (order.isLimitOrder()) {
                cancelLog.put("limitPrice", order.getLimitPrice());
            }
            
            if (order.isStopOrder()) {
                cancelLog.put("stopPrice", ((StopOrder) order).getStopPrice());
            }
            
            String logLine = cancelLog.toString() + "\n";
            synchronized (orderUpdatesLock) {
            Files.write(Paths.get(ORDER_UPDATES_FILE),
                    logLine.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            }

            
        } catch (Exception e) {
            System.err.println("Errore nel log order cancellation: " + e.getMessage());
        }
    }

}

