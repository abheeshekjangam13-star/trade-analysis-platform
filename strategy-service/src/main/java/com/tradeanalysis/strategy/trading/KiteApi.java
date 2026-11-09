package com.tradeanalysis.strategy.trading;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** The Kite Connect calls trading needs. {@link KiteRestApi} talks to api.kite.trade; tests use fakes. */
public interface KiteApi {

    record Profile(String userId, String userName, String broker) {}
    record Margins(double available, double net, double utilised) {}
    record BrokerPosition(String tradingsymbol, String exchange, String product, int quantity, double averagePrice,
                          double lastPrice, double pnl) {}
    record BrokerOrder(String orderId, String status, String tradingsymbol, String exchange, String transactionType,
                       int quantity, int filledQuantity, double averagePrice, Double price, String orderType,
                       String product, String statusMessage, String tag, String timestamp) {}
    record PlaceOrder(String exchange, String tradingsymbol, String transactionType, int quantity, String product,
                      String orderType, Double price, Double triggerPrice, String tag) {}
    /** One index option contract from the NFO instruments list. */
    record OptionInstrument(String tradingsymbol, LocalDate expiry, int strike, String optionType, int lotSize,
                            double tickSize) {}

    Profile profile();
    Margins margins();
    List<BrokerPosition> positions();
    List<BrokerOrder> orders();
    BrokerOrder order(String orderId);
    String placeOrder(PlaceOrder order);
    void cancelOrder(String orderId);
    /** Last traded prices keyed by "EXCHANGE:TRADINGSYMBOL". */
    Map<String, Double> ltp(List<String> instruments);
    /** Option contracts of an index currently listed on NFO (NIFTY or BANKNIFTY). */
    List<OptionInstrument> optionsOf(com.tradeanalysis.common.market.Underlying underlying);
}
