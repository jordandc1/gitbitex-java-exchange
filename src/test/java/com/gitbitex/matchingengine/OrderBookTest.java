package com.gitbitex.matchingengine;

import com.gitbitex.enums.OrderSide;
import com.gitbitex.enums.OrderStatus;
import com.gitbitex.enums.OrderType;
import com.gitbitex.matchingengine.command.PlaceOrderCommand;
import com.gitbitex.matchingengine.command.PutProductCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
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
        assertEquals(BigDecimal.valueOf(2),buyerBTCAccount.getAvailable().add(buyerBTCAccount.getHold())
                .add(sellerBTCAccount.getAvailable()).add(sellerBTCAccount.getHold()));
        assertEquals(BigDecimal.valueOf(220),buyerUSDTAccount.getAvailable().add(buyerUSDTAccount.getHold())
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
        assertEquals(BigDecimal.valueOf(2),buyerBTCAccount.getAvailable().add(buyerBTCAccount.getHold())
                .add(sellerABTCAccount.getAvailable()).add(sellerABTCAccount.getHold()).add(sellerBBTCAccount.getHold())
                .add(sellerBBTCAccount.getAvailable()));
        assertEquals(BigDecimal.valueOf(110),buyerUSDTAccount.getAvailable().add(buyerUSDTAccount.getHold())
                .add(sellerAUSDTAccount.getAvailable()).add(sellerAUSDTAccount.getHold()).add(sellerBUSDTAccount
                        .getHold()).add(sellerBUSDTAccount.getAvailable()));
    }

}
