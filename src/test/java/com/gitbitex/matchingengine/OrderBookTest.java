package com.gitbitex.matchingengine;

import com.gitbitex.enums.OrderSide;
import com.gitbitex.enums.OrderStatus;
import com.gitbitex.enums.OrderType;
import com.gitbitex.matchingengine.command.PlaceOrderCommand;
import com.gitbitex.matchingengine.command.PutProductCommand;
import com.gitbitex.matchingengine.message.AccountMessage;
import com.gitbitex.matchingengine.message.Message;
import com.gitbitex.matchingengine.message.OrderMessage;
import com.gitbitex.matchingengine.message.TradeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderBookTest {
    private OrderBook orderBook;
    private AccountBook accountBook;
    private ProductBook productBook;
    private MessageSender messageSender;
    private AtomicLong messageSequence;
    private PutProductCommand putProductCommand;
    private PlaceOrderCommand placeOrderCommand;

    @BeforeEach
    void setUp() {
        messageSender = mock(MessageSender.class);
        messageSequence = new AtomicLong(0);
        accountBook = new AccountBook(messageSender, messageSequence);
        productBook = new ProductBook(messageSender, messageSequence);
        orderBook = new OrderBook(
                "BTC-USDT",
                0,
                0,
                0,
                accountBook,
                productBook,
                messageSender,
                messageSequence
        );
        //add product BTC-USDT
        putProductCommand = new PutProductCommand();
        putProductCommand.setProductId("BTC-USDT");
        putProductCommand.setBaseCurrency("BTC");
        putProductCommand.setQuoteCurrency("USDT");
        productBook.addProduct(new Product(putProductCommand));
    }

    @Test
    void placeNonCrossingLimitBuyRestsOnBookAndHoldsQuoteFunds() {
        //add product BTC-USDT
        putProductCommand = new PutProductCommand();
        putProductCommand.setProductId("BTC-USDT");
        putProductCommand.setBaseCurrency("BTC");
        putProductCommand.setQuoteCurrency("USDT");
        productBook.addProduct(new Product(putProductCommand));

        //add account with 1000 balance
        Account account = new Account();
        account.setId("1");
        account.setUserId("user1");
        account.setCurrency("USDT");
        account.setAvailable(BigDecimal.valueOf(1000));
        account.setHold(BigDecimal.ZERO);
        accountBook.add(account);

        //create placeOrderCommand with size*price 10*10
        placeOrderCommand = new PlaceOrderCommand();
        placeOrderCommand.setProductId("BTC-USDT");
        placeOrderCommand.setOrderId("1");
        placeOrderCommand.setUserId("user1");
        placeOrderCommand.setSize(BigDecimal.TEN);
        placeOrderCommand.setPrice(BigDecimal.TEN);
        placeOrderCommand.setOrderSide(OrderSide.BUY);
        placeOrderCommand.setOrderType(OrderType.LIMIT);
        placeOrderCommand.setTime(new Date(0));

        //create order
        Order order = new Order(placeOrderCommand);

        //add to order book
        orderBook.placeOrder(order);

        assertEquals(OrderStatus.OPEN, order.getStatus());
        assertTrue(orderBook.getBids().containsKey(BigDecimal.TEN));
        assertEquals(orderBook.getBids().get(BigDecimal.TEN).get("1"), order);
        assertEquals(orderBook.getAsks().size(), 0);
        assertEquals(account.getAvailable(), BigDecimal.valueOf(900));
        assertEquals(account.getAvailable().add(account.getHold()), BigDecimal.valueOf(1000));
        assertEquals(account.getHold(), BigDecimal.valueOf(100));

    }

    @Test
    void crossingLimitBuyFullyMatchesRestingSellAtMakerPrice() {
        //add product BTC-USDT
        putProductCommand = new PutProductCommand();
        putProductCommand.setProductId("BTC-USDT");
        putProductCommand.setBaseCurrency("BTC");
        putProductCommand.setQuoteCurrency("USDT");
        productBook.addProduct(new Product(putProductCommand));

        // Seller starts with 2 BTC.
        Account sellerBTCAccount = new Account();
        sellerBTCAccount.setId("1");
        sellerBTCAccount.setUserId("seller");
        sellerBTCAccount.setCurrency("BTC");
        sellerBTCAccount.setAvailable(BigDecimal.valueOf(2));
        sellerBTCAccount.setHold(BigDecimal.ZERO);
        Account sellerUSDTAccount = new Account();
        sellerUSDTAccount.setUserId("seller");
        sellerUSDTAccount.setCurrency("USDT");
        sellerUSDTAccount.setAvailable(BigDecimal.ZERO);
        sellerUSDTAccount.setHold(BigDecimal.ZERO);
        accountBook.add(sellerBTCAccount);
        accountBook.add(sellerUSDTAccount);
        // Seller places a limit sell for 2 BTC @ 100 USDT.
        PlaceOrderCommand sellCommand = new PlaceOrderCommand();
        sellCommand.setProductId("BTC-USDT");
        sellCommand.setOrderId("sell-1");
        sellCommand.setUserId("seller");
        sellCommand.setSize(BigDecimal.valueOf(2));
        sellCommand.setPrice(BigDecimal.valueOf(100));
        sellCommand.setOrderType(OrderType.LIMIT);
        sellCommand.setOrderSide(OrderSide.SELL);
        sellCommand.setTime(new Date(0));
        Order sellOrder = new Order(sellCommand);
        orderBook.placeOrder(sellOrder);
        // Confirm the sell rests as OPEN.
        assertEquals(OrderStatus.OPEN, sellOrder.getStatus());
        assertEquals(orderBook.getAsks().get(BigDecimal.valueOf(100)).get("sell-1"), sellOrder);
        // Buyer starts with 220 USDT.
        Account buyerBTCAccount = new Account();
        buyerBTCAccount.setId("2");
        buyerBTCAccount.setUserId("buyer");
        buyerBTCAccount.setCurrency("BTC");
        buyerBTCAccount.setAvailable(BigDecimal.ZERO);
        buyerBTCAccount.setHold(BigDecimal.ZERO);
        Account buyerUSDTAccount = new Account();
        buyerUSDTAccount.setUserId("buyer");
        buyerUSDTAccount.setCurrency("USDT");
        buyerUSDTAccount.setAvailable(BigDecimal.valueOf(220));
        buyerUSDTAccount.setHold(BigDecimal.ZERO);
        accountBook.add(buyerBTCAccount);
        accountBook.add(buyerUSDTAccount);
        // Buyer places a crossing limit buy for 2 BTC @ 110 USDT.
        PlaceOrderCommand buyCommand = new PlaceOrderCommand();
        buyCommand.setProductId("BTC-USDT");
        buyCommand.setOrderId("buy-1");
        buyCommand.setUserId("buyer");
        buyCommand.setSize(BigDecimal.valueOf(2));
        buyCommand.setPrice(BigDecimal.valueOf(110));
        buyCommand.setOrderType(OrderType.LIMIT);
        buyCommand.setOrderSide(OrderSide.BUY);
        buyCommand.setTime(new Date(0));
        Order buyOrder = new Order(buyCommand);
        orderBook.placeOrder(buyOrder);
        // The trade should execute for 2 BTC @ 100 USDT.
        assertEquals(OrderStatus.FILLED, sellOrder.getStatus());
        assertEquals(OrderStatus.FILLED, buyOrder.getStatus());
        assertEquals(0, orderBook.getAsks().size());
        assertEquals(0, orderBook.getBids().size());
        assertEquals(buyerBTCAccount.getAvailable(), BigDecimal.valueOf(2));
        assertEquals(buyerUSDTAccount.getAvailable(), BigDecimal.valueOf(20));
        assertEquals(BigDecimal.ZERO, buyerUSDTAccount.getHold());
        assertEquals(BigDecimal.ZERO, buyerBTCAccount.getHold());
        assertEquals(BigDecimal.ZERO, sellerBTCAccount.getAvailable());
        assertEquals(BigDecimal.ZERO, sellerBTCAccount.getHold());
        assertEquals(BigDecimal.ZERO, sellerUSDTAccount.getHold());
        assertEquals(sellerUSDTAccount.getAvailable(), BigDecimal.valueOf(200));
        assertEquals(BigDecimal.valueOf(2), buyerBTCAccount.getAvailable().add(buyerBTCAccount.getHold())
                .add(sellerBTCAccount.getAvailable()).add(sellerBTCAccount.getHold()));
        assertEquals(BigDecimal.valueOf(220), buyerUSDTAccount.getAvailable().add(buyerUSDTAccount.getHold())
                .add(sellerUSDTAccount.getAvailable()).add(sellerUSDTAccount.getHold()));

    }

    @Test
    void crossingLimitBuyMatchesBestAskRegardlessOfInsertionOrder() {
        //add product BTC-USDT
        putProductCommand = new PutProductCommand();
        putProductCommand.setProductId("BTC-USDT");
        putProductCommand.setBaseCurrency("BTC");
        putProductCommand.setQuoteCurrency("USDT");
        productBook.addProduct(new Product(putProductCommand));

        //Seller A places 1 BTC @ 110 USDT.
        Account sellerABTCAccount = new Account();
        sellerABTCAccount.setId("1");
        sellerABTCAccount.setUserId("seller-a");
        sellerABTCAccount.setCurrency("BTC");
        sellerABTCAccount.setAvailable(BigDecimal.valueOf(1));
        sellerABTCAccount.setHold(BigDecimal.ZERO);
        Account sellerAUSDTAccount = new Account();
        sellerAUSDTAccount.setUserId("seller-a");
        sellerAUSDTAccount.setCurrency("USDT");
        sellerAUSDTAccount.setAvailable(BigDecimal.ZERO);
        sellerAUSDTAccount.setHold(BigDecimal.ZERO);
        accountBook.add(sellerABTCAccount);
        accountBook.add(sellerAUSDTAccount);
        PlaceOrderCommand sellCommand = new PlaceOrderCommand();
        sellCommand.setProductId("BTC-USDT");
        sellCommand.setOrderId("sell-1");
        sellCommand.setUserId("seller-a");
        sellCommand.setSize(BigDecimal.valueOf(1));
        sellCommand.setPrice(BigDecimal.valueOf(110));
        sellCommand.setOrderType(OrderType.LIMIT);
        sellCommand.setOrderSide(OrderSide.SELL);
        sellCommand.setTime(new Date(0));
        Order sellOrder = new Order(sellCommand);
        orderBook.placeOrder(sellOrder);
        //Seller B then places 1 BTC @ 100 USDT.
        Account sellerBBTCAccount = new Account();
        sellerBBTCAccount.setId("2");
        sellerBBTCAccount.setUserId("seller-b");
        sellerBBTCAccount.setCurrency("BTC");
        sellerBBTCAccount.setAvailable(BigDecimal.valueOf(1));
        sellerBBTCAccount.setHold(BigDecimal.ZERO);
        Account sellerBUSDTAccount = new Account();
        sellerBUSDTAccount.setUserId("seller-b");
        sellerBUSDTAccount.setCurrency("USDT");
        sellerBUSDTAccount.setAvailable(BigDecimal.ZERO);
        sellerBUSDTAccount.setHold(BigDecimal.ZERO);
        accountBook.add(sellerBBTCAccount);
        accountBook.add(sellerBUSDTAccount);
        PlaceOrderCommand sell2Command = new PlaceOrderCommand();
        sell2Command.setProductId("BTC-USDT");
        sell2Command.setOrderId("sell-2");
        sell2Command.setUserId("seller-b");
        sell2Command.setSize(BigDecimal.valueOf(1));
        sell2Command.setPrice(BigDecimal.valueOf(100));
        sell2Command.setOrderType(OrderType.LIMIT);
        sell2Command.setOrderSide(OrderSide.SELL);
        sell2Command.setTime(new Date(0));
        Order sell2Order = new Order(sell2Command);
        orderBook.placeOrder(sell2Order);
        assertEquals(OrderStatus.OPEN, sellOrder.getStatus());
        assertEquals(OrderStatus.OPEN, sell2Order.getStatus());
        assertTrue(orderBook.getAsks().containsKey(BigDecimal.valueOf(100)));
        assertTrue(orderBook.getAsks().containsKey(BigDecimal.valueOf(110)));
        assertTrue(sellOrder.getSequence() < sell2Order.getSequence());
        //Buyer places 1 BTC @ 110 USDT.
        Account buyerBTCAccount = new Account();
        buyerBTCAccount.setId("3");
        buyerBTCAccount.setUserId("buyer");
        buyerBTCAccount.setCurrency("BTC");
        buyerBTCAccount.setAvailable(BigDecimal.ZERO);
        buyerBTCAccount.setHold(BigDecimal.ZERO);
        Account buyerUSDTAccount = new Account();
        buyerUSDTAccount.setUserId("buyer");
        buyerUSDTAccount.setCurrency("USDT");
        buyerUSDTAccount.setAvailable(BigDecimal.valueOf(110));
        buyerUSDTAccount.setHold(BigDecimal.ZERO);
        accountBook.add(buyerBTCAccount);
        accountBook.add(buyerUSDTAccount);
        PlaceOrderCommand buyCommand = new PlaceOrderCommand();
        buyCommand.setProductId("BTC-USDT");
        buyCommand.setOrderId("buy-1");
        buyCommand.setUserId("buyer");
        buyCommand.setSize(BigDecimal.valueOf(1));
        buyCommand.setPrice(BigDecimal.valueOf(110));
        buyCommand.setOrderType(OrderType.LIMIT);
        buyCommand.setOrderSide(OrderSide.BUY);
        buyCommand.setTime(new Date(0));
        Order buyOrder = new Order(buyCommand);
        orderBook.placeOrder(buyOrder);
        //The buyer must match Seller B at 100, not Seller A at 110.
        assertEquals(OrderStatus.OPEN, sellOrder.getStatus());
        assertEquals(OrderStatus.FILLED, sell2Order.getStatus());
        assertEquals(OrderStatus.FILLED, buyOrder.getStatus());
        assertFalse(orderBook.getAsks().containsKey(BigDecimal.valueOf(100)));
        assertTrue(orderBook.getAsks().containsKey(BigDecimal.valueOf(110)));
        assertTrue(orderBook.getAsks().get(BigDecimal.valueOf(110)).containsKey("sell-1"));
        assertTrue(orderBook.getBids().isEmpty());
        assertEquals(buyerBTCAccount.getAvailable(), BigDecimal.valueOf(1));
        assertEquals(BigDecimal.ZERO, buyerBTCAccount.getHold());
        assertEquals(BigDecimal.TEN, buyerUSDTAccount.getAvailable());
        assertEquals(BigDecimal.ZERO, buyerUSDTAccount.getHold());
        assertEquals(sellerABTCAccount.getHold(), BigDecimal.valueOf(1));
        assertEquals(sellerBUSDTAccount.getAvailable(), BigDecimal.valueOf(100));
        assertEquals(BigDecimal.valueOf(2), buyerBTCAccount.getAvailable().add(buyerBTCAccount.getHold())
                .add(sellerABTCAccount.getAvailable()).add(sellerABTCAccount.getHold()).add(sellerBBTCAccount.getHold())
                .add(sellerBBTCAccount.getAvailable()));
        assertEquals(BigDecimal.valueOf(110), buyerUSDTAccount.getAvailable().add(buyerUSDTAccount.getHold())
                .add(sellerAUSDTAccount.getAvailable()).add(sellerAUSDTAccount.getHold()).add(sellerBUSDTAccount
                        .getHold()).add(sellerBUSDTAccount.getAvailable()));
    }

    @Test
    void crossingLimitBuyHonorsPriceTimePriorityAndSettlesAccounts() {
        // worse-maker: sell 2 BTC at 105 USDT
        Account worseMakerBTC = new Account();
        worseMakerBTC.setId("1");
        worseMakerBTC.setUserId("worseMaker");
        worseMakerBTC.setCurrency("BTC");
        worseMakerBTC.setAvailable(BigDecimal.valueOf(2));
        worseMakerBTC.setHold(BigDecimal.ZERO);
        Account worseMakerUSDT = new Account();
        worseMakerUSDT.setId("2");
        worseMakerUSDT.setUserId("worseMaker");
        worseMakerUSDT.setCurrency("USDT");
        worseMakerUSDT.setAvailable(BigDecimal.ZERO);
        worseMakerUSDT.setHold(BigDecimal.ZERO);
        accountBook.add(worseMakerBTC);
        accountBook.add(worseMakerUSDT);
        PlaceOrderCommand worseMakerCommand = new PlaceOrderCommand();
        worseMakerCommand.setProductId("BTC-USDT");
        worseMakerCommand.setOrderId("1");
        worseMakerCommand.setUserId("worseMaker");
        worseMakerCommand.setSize(BigDecimal.valueOf(2));
        worseMakerCommand.setPrice(BigDecimal.valueOf(105));
        worseMakerCommand.setOrderType(OrderType.LIMIT);
        worseMakerCommand.setOrderSide(OrderSide.SELL);
        worseMakerCommand.setTime(new Date(0));
        Order worseMakerOrder = new Order(worseMakerCommand);
        orderBook.placeOrder(worseMakerOrder);
        assertBalance(worseMakerBTC, 0, 2);

        // best-old: sell 2 BTC at 100 USDT.
        Account bestOldBTC = new Account();
        bestOldBTC.setId("3");
        bestOldBTC.setUserId("bestOld");
        bestOldBTC.setCurrency("BTC");
        bestOldBTC.setAvailable(BigDecimal.valueOf(2));
        bestOldBTC.setHold(BigDecimal.ZERO);
        Account bestOldUSDT = new Account();
        bestOldUSDT.setId("4");
        bestOldUSDT.setUserId("bestOld");
        bestOldUSDT.setCurrency("USDT");
        bestOldUSDT.setAvailable(BigDecimal.ZERO);
        bestOldUSDT.setHold(BigDecimal.ZERO);
        accountBook.add(bestOldBTC);
        accountBook.add(bestOldUSDT);
        PlaceOrderCommand bestOldCommand = new PlaceOrderCommand();
        bestOldCommand.setProductId("BTC-USDT");
        bestOldCommand.setOrderId("2");
        bestOldCommand.setUserId("bestOld");
        bestOldCommand.setSize(BigDecimal.valueOf(2));
        bestOldCommand.setPrice(BigDecimal.valueOf(100));
        bestOldCommand.setOrderType(OrderType.LIMIT);
        bestOldCommand.setOrderSide(OrderSide.SELL);
        bestOldCommand.setTime(new Date(0));
        Order bestOldOrder = new Order(bestOldCommand);
        orderBook.placeOrder(bestOldOrder);
        assertBalance(bestOldBTC, 0, 2);

        // best-new: sell 2 BTC at 100 USDT.
        Account bestNewBTC = new Account();
        bestNewBTC.setId("5");
        bestNewBTC.setUserId("bestNew");
        bestNewBTC.setCurrency("BTC");
        bestNewBTC.setAvailable(BigDecimal.valueOf(2));
        bestNewBTC.setHold(BigDecimal.ZERO);
        Account bestNewUSDT = new Account();
        bestNewUSDT.setId("6");
        bestNewUSDT.setUserId("bestNew");
        bestNewUSDT.setCurrency("USDT");
        bestNewUSDT.setAvailable(BigDecimal.ZERO);
        bestNewUSDT.setHold(BigDecimal.ZERO);
        accountBook.add(bestNewBTC);
        accountBook.add(bestNewUSDT);
        PlaceOrderCommand bestNewCommand = new PlaceOrderCommand();
        bestNewCommand.setProductId("BTC-USDT");
        bestNewCommand.setOrderId("3");
        bestNewCommand.setUserId("bestNew");
        bestNewCommand.setSize(BigDecimal.valueOf(2));
        bestNewCommand.setPrice(BigDecimal.valueOf(100));
        bestNewCommand.setOrderType(OrderType.LIMIT);
        bestNewCommand.setOrderSide(OrderSide.SELL);
        bestNewCommand.setTime(new Date(0));
        Order bestNewOrder = new Order(bestNewCommand);
        orderBook.placeOrder(bestNewOrder);
        assertBalance(bestNewBTC, 0, 2);

        // buyer: buy 3 BTC with a limit of 110 USDT and an initial 400 USDT balance.
        Account buyerBTC = new Account();
        buyerBTC.setId("7");
        buyerBTC.setUserId("buyer");
        buyerBTC.setCurrency("BTC");
        buyerBTC.setAvailable(BigDecimal.ZERO);
        buyerBTC.setHold(BigDecimal.ZERO);
        Account buyerUSDT = new Account();
        buyerUSDT.setId("8");
        buyerUSDT.setUserId("buyer");
        buyerUSDT.setCurrency("USDT");
        buyerUSDT.setAvailable(BigDecimal.valueOf(400));
        buyerUSDT.setHold(BigDecimal.ZERO);
        accountBook.add(buyerBTC);
        accountBook.add(buyerUSDT);
        PlaceOrderCommand buyerCommand = new PlaceOrderCommand();
        buyerCommand.setProductId("BTC-USDT");
        buyerCommand.setOrderId("4");
        buyerCommand.setUserId("buyer");
        buyerCommand.setSize(BigDecimal.valueOf(3));
        buyerCommand.setPrice(BigDecimal.valueOf(110));
        buyerCommand.setOrderType(OrderType.LIMIT);
        buyerCommand.setOrderSide(OrderSide.BUY);
        buyerCommand.setTime(new Date(0));
        Order buyerOrder = new Order(buyerCommand);
        orderBook.placeOrder(buyerOrder);

        ArgumentCaptor<Message> messageCaptor =
                ArgumentCaptor.forClass(Message.class);

        verify(messageSender, atLeastOnce())
                .send(messageCaptor.capture());

        List<Message> messages = messageCaptor.getAllValues();

        List<Trade> trades = messages.stream()
                .filter(TradeMessage.class::isInstance)
                .map(TradeMessage.class::cast)
                .map(TradeMessage::getTrade)
                .toList();

        assertEquals(2, trades.size());

        Trade firstTrade = trades.get(0);
        assertEquals("2", firstTrade.getMakerOrderId());
        assertEquals("4", firstTrade.getTakerOrderId());
        assertEquals(BigDecimal.valueOf(2), firstTrade.getSize());
        assertEquals(BigDecimal.valueOf(100), firstTrade.getPrice());
        assertEquals(BigDecimal.valueOf(200), firstTrade.getFunds());

        Trade secondTrade = trades.get(1);
        assertEquals("3", secondTrade.getMakerOrderId());
        assertEquals("4", secondTrade.getTakerOrderId());
        assertEquals(BigDecimal.ONE, secondTrade.getSize());
        assertEquals(BigDecimal.valueOf(100), secondTrade.getPrice());
        assertEquals(BigDecimal.valueOf(100), secondTrade.getFunds());

        List<Account> buyerUsdtSnapshots = messages.stream()
                .filter(AccountMessage.class::isInstance)
                .map(AccountMessage.class::cast)
                .map(AccountMessage::getAccount)
                .filter(account -> "buyer".equals(account.getUserId()))
                .filter(account -> "USDT".equals(account.getCurrency()))
                .toList();

        assertEquals(4, buyerUsdtSnapshots.size());

        assertBalance(buyerUsdtSnapshots.get(0), 70, 330);
        assertBalance(buyerUsdtSnapshots.get(1), 70, 130);
        assertBalance(buyerUsdtSnapshots.get(2), 70, 30);
        assertBalance(buyerUsdtSnapshots.get(3), 100, 0);

        assertTrue(orderBook.getBids().isEmpty());

        assertEquals(2, orderBook.getAsks().size());

        // Best-price level: only the partially filled bestNew order remains.
        PriceGroupedOrderCollection bestAskLevel =
                orderBook.getAsks().get(BigDecimal.valueOf(100));

        assertNotNull(bestAskLevel);
        assertEquals(1, bestAskLevel.size());
        assertSame(bestNewOrder, bestAskLevel.get("3"));
        assertEquals(
                0,
                BigDecimal.ONE.compareTo(bestNewOrder.getRemainingSize())
        );
        assertFalse(bestAskLevel.containsKey("2"));

        // Worse-price level remains completely untouched.
        PriceGroupedOrderCollection worseAskLevel =
                orderBook.getAsks().get(BigDecimal.valueOf(105));

        assertNotNull(worseAskLevel);
        assertEquals(1, worseAskLevel.size());
        assertSame(worseMakerOrder, worseAskLevel.get("1"));
        assertEquals(
                0,
                BigDecimal.valueOf(2)
                        .compareTo(worseMakerOrder.getRemainingSize())
        );

        // Filled orders must not remain in the order-ID index.
        assertFalse(orderBook.getOrderById().containsKey("2"));
        assertFalse(orderBook.getOrderById().containsKey("4"));

        // Open orders must remain indexed.
        assertSame(bestNewOrder, orderBook.getOrderById().get("3"));
        assertSame(worseMakerOrder, orderBook.getOrderById().get("1"));

        assertEquals(OrderStatus.FILLED, buyerOrder.getStatus());
        assertEquals(OrderStatus.FILLED, bestOldOrder.getStatus());
        assertEquals(OrderStatus.OPEN, bestNewOrder.getStatus());
        assertEquals(OrderStatus.OPEN, worseMakerOrder.getStatus());

        assertEquals(buyerBTC.getAvailable(), BigDecimal.valueOf(3));
        assertEquals(buyerBTC.getHold(), BigDecimal.valueOf(0));
        assertEquals(buyerUSDT.getAvailable(), BigDecimal.valueOf(100));
        assertEquals(buyerUSDT.getHold(), BigDecimal.valueOf(0));

        assertEquals(bestOldBTC.getAvailable(), BigDecimal.valueOf(0));
        assertEquals(bestOldBTC.getHold(), BigDecimal.valueOf(0));
        assertEquals(bestOldUSDT.getAvailable(), BigDecimal.valueOf(200));
        assertEquals(bestOldUSDT.getHold(), BigDecimal.valueOf(0));

        assertEquals(bestNewBTC.getAvailable(), BigDecimal.valueOf(0));
        assertEquals(bestNewBTC.getHold(), BigDecimal.valueOf(1));
        assertEquals(bestNewUSDT.getAvailable(), BigDecimal.valueOf(100));
        assertEquals(bestNewUSDT.getHold(), BigDecimal.valueOf(0));

        assertEquals(worseMakerBTC.getAvailable(), BigDecimal.valueOf(0));
        assertEquals(worseMakerBTC.getHold(), BigDecimal.valueOf(2));
        assertEquals(worseMakerUSDT.getAvailable(), BigDecimal.valueOf(0));
        assertEquals(worseMakerUSDT.getHold(), BigDecimal.valueOf(0));

        assertTrue(bestOldOrder.getSequence() < bestNewOrder.getSequence());
    }

    @Test
    void cancelPartiallyFilledLimitBuyReleasesOnlyRemainingQuoteHold() {
        // Buyer starts with 500 USDT and 0 BTC.
        Account buyerBTC = createAccount("1", "buyer", "BTC", BigDecimal.ZERO, BigDecimal.ZERO);
        Account buyerUSDT = createAccount("2", "buyer", "USDT", BigDecimal.valueOf(500),
                BigDecimal.ZERO);
        accountBook.add(buyerBTC);
        accountBook.add(buyerUSDT);
        // Buyer rests a limit buy for 5 BTC at 100:USDT becomes available=0, hold=500.
        PlaceOrderCommand buyerCommand = createPlaceOrderCommand("BTC-USDT", "1", "buyer",
                BigDecimal.valueOf(5), BigDecimal.valueOf(100), OrderType.LIMIT, OrderSide.BUY);
        Order buyerOrder = new Order(buyerCommand);
        orderBook.placeOrder(buyerOrder);
        // Seller starts with 2 BTC and submits a crossing limit sell for 2 BTC at 90.
        Account sellerBTC = createAccount("3", "seller", "BTC", BigDecimal.valueOf(2), BigDecimal.ZERO);
        Account sellerUSDT = createAccount("4", "seller", "USDT", BigDecimal.ZERO,
                BigDecimal.ZERO);
        accountBook.add(sellerBTC);
        accountBook.add(sellerUSDT);
        PlaceOrderCommand sellerCommand = createPlaceOrderCommand("BTC-USDT", "2", "seller",
                BigDecimal.valueOf(2), BigDecimal.valueOf(90), OrderType.LIMIT, OrderSide.SELL);
        Order sellOrder = new Order(sellerCommand);
        orderBook.placeOrder(sellOrder);

        // Trade executes at the resting maker price of 100.
        ArgumentCaptor<Message> messageCaptor =
                ArgumentCaptor.forClass(Message.class);

        verify(messageSender, atLeastOnce())
                .send(messageCaptor.capture());

        List<Message> messages = messageCaptor.getAllValues();

        List<Trade> trades = messages.stream()
                .filter(TradeMessage.class::isInstance)
                .map(TradeMessage.class::cast)
                .map(TradeMessage::getTrade)
                .toList();

        assertEquals(1, trades.size());

        Trade firstTrade = trades.get(0);
        assertEquals("1", firstTrade.getMakerOrderId());
        assertEquals("2", firstTrade.getTakerOrderId());
        assertEquals(BigDecimal.valueOf(2), firstTrade.getSize());
        assertEquals(BigDecimal.valueOf(100), firstTrade.getPrice());
        assertEquals(BigDecimal.valueOf(200), firstTrade.getFunds());

        assertEquals(OrderStatus.OPEN, buyerOrder.getStatus());
        assertEquals(
                0,
                BigDecimal.valueOf(3)
                        .compareTo(buyerOrder.getRemainingSize())
        );
        assertEquals(
                0,
                BigDecimal.valueOf(300)
                        .compareTo(buyerOrder.getRemainingFunds())
        );
        assertSame(
                buyerOrder,
                orderBook.getBids()
                        .get(BigDecimal.valueOf(100))
                        .get("1")
        );
        assertBalance(buyerUSDT, 0, 300);

        // Forget placement/trade invocations, while keeping the same mock.
        clearInvocations(messageSender);
        // Cancel the buyer’s remaining open order.
        orderBook.cancelOrder(buyerOrder.getId());

        assertEquals(OrderStatus.CANCELLED, buyerOrder.getStatus());
        assertEquals(OrderStatus.FILLED, sellOrder.getStatus());

        assertTrue(orderBook.getBids().isEmpty());
        assertFalse(orderBook.getOrderById().containsKey(buyerOrder.getId()));

        assertBalance(buyerBTC, 2, 0);
        assertBalance(buyerUSDT, 300, 0);
        assertBalance(sellerBTC, 0, 0);
        assertBalance(sellerUSDT, 200, 0);

        ArgumentCaptor<Message> cancelCaptor =
                ArgumentCaptor.forClass(Message.class);

        verify(messageSender, times(2))
                .send(cancelCaptor.capture());

        List<Message> cancelMessages = cancelCaptor.getAllValues();

        List<OrderMessage> cancellationOrderMessages = cancelMessages.stream()
                .filter(OrderMessage.class::isInstance)
                .map(OrderMessage.class::cast)
                .toList();

        assertEquals(1, cancellationOrderMessages.size());

        Order cancelledSnapshot =
                cancellationOrderMessages.get(0).getOrder();

        assertEquals(buyerOrder.getId(), cancelledSnapshot.getId());
        assertEquals(OrderStatus.CANCELLED, cancelledSnapshot.getStatus());
        assertNotSame(buyerOrder, cancelledSnapshot);

        List<AccountMessage> cancellationAccountMessages = cancelMessages.stream()
                .filter(AccountMessage.class::isInstance)
                .map(AccountMessage.class::cast)
                .filter(message ->
                        "buyer".equals(message.getAccount().getUserId()))
                .filter(message ->
                        "USDT".equals(message.getAccount().getCurrency()))
                .toList();

        assertEquals(1, cancellationAccountMessages.size());
        assertBalance(
                cancellationAccountMessages.get(0).getAccount(),
                300,
                0
        );

        BigDecimal totalBTC = buyerBTC.getAvailable()
                .add(buyerBTC.getHold())
                .add(sellerBTC.getAvailable())
                .add(sellerBTC.getHold());

        BigDecimal totalUSDT = buyerUSDT.getAvailable()
                .add(buyerUSDT.getHold())
                .add(sellerUSDT.getAvailable())
                .add(sellerUSDT.getHold());

        assertEquals(0, BigDecimal.valueOf(2).compareTo(totalBTC));
        assertEquals(0, BigDecimal.valueOf(500).compareTo(totalUSDT));

    }

    private static void assertBalance(
            Account account,
            long expectedAvailable,
            long expectedHold
    ) {
        assertEquals(
                0,
                BigDecimal.valueOf(expectedAvailable)
                        .compareTo(account.getAvailable())
        );
        assertEquals(
                0,
                BigDecimal.valueOf(expectedHold)
                        .compareTo(account.getHold())
        );
    }

    private Account createAccount(String id, String userId, String currency, BigDecimal available, BigDecimal hold) {
        Account account = new Account();
        account.setId(id);
        account.setUserId(userId);
        account.setCurrency(currency);
        account.setAvailable(available);
        account.setHold(hold);
        return account;
    }

    private PlaceOrderCommand createPlaceOrderCommand(String productId, String orderId, String userId, BigDecimal size,
                                                      BigDecimal price, OrderType orderType, OrderSide orderSide) {
        PlaceOrderCommand placeOrderCommand = new PlaceOrderCommand();
        placeOrderCommand.setProductId(productId);
        placeOrderCommand.setOrderId(orderId);
        placeOrderCommand.setUserId(userId);
        placeOrderCommand.setSize(size);
        placeOrderCommand.setPrice(price);
        placeOrderCommand.setOrderType(orderType);
        placeOrderCommand.setOrderSide(orderSide);
        placeOrderCommand.setTime(new Date(0));
        return placeOrderCommand;
    }
}
