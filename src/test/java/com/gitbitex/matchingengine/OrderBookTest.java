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

}
