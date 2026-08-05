package com.gitbitex.matchingengine;

import com.gitbitex.enums.OrderSide;
import com.gitbitex.enums.OrderStatus;
import com.gitbitex.enums.OrderType;
import com.gitbitex.matchingengine.command.PlaceOrderCommand;
import com.gitbitex.matchingengine.message.CommandEndMessage;
import com.gitbitex.matchingengine.message.CommandStartMessage;
import com.gitbitex.matchingengine.message.Message;
import com.gitbitex.matchingengine.message.OrderMessage;
import com.gitbitex.matchingengine.message.TradeMessage;
import com.gitbitex.matchingengine.snapshot.EngineSnapshotManager;
import com.gitbitex.matchingengine.snapshot.EngineState;
import com.mongodb.client.ClientSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MatchingEngineTest {

    @Test
    void restoredSnapshotContinuesSequencesAndMatchesRestingOrderDeterministically() {
        // 1. Infrastructure mocks
        EngineSnapshotManager stateStore = mock(EngineSnapshotManager.class);
        ClientSession session = mock(ClientSession.class);
        MessageSender messageSender = mock(MessageSender.class);

        // 2. Restored engine counters
        EngineState engineState = new EngineState();
        engineState.setCommandOffset(42L);
        engineState.setMessageSequence(100L);
        engineState.getOrderSequences()
                .put("BTC-USDT", 7L);
        engineState.getTradeSequences()
                .put("BTC-USDT", 9L);
        engineState.getOrderBookSequences()
                .put("BTC-USDT", 11L);

        // 3. Restored product
        Product product = new Product();
        product.setId("BTC-USDT");
        product.setBaseCurrency("BTC");
        product.setQuoteCurrency("USDT");

        // 4. Restored accounts
        Account sellerBTC = new Account();
        sellerBTC.setId("seller-BTC");
        sellerBTC.setUserId("seller");
        sellerBTC.setCurrency("BTC");
        sellerBTC.setAvailable(BigDecimal.ZERO);
        sellerBTC.setHold(BigDecimal.valueOf(2));

        Account sellerUSDT = new Account();
        sellerUSDT.setId("seller-USDT");
        sellerUSDT.setUserId("seller");
        sellerUSDT.setCurrency("USDT");
        sellerUSDT.setAvailable(BigDecimal.ZERO);
        sellerUSDT.setHold(BigDecimal.ZERO);

        Account buyerBTC = new Account();
        buyerBTC.setId("buyer-BTC");
        buyerBTC.setUserId("buyer");
        buyerBTC.setCurrency("BTC");
        buyerBTC.setAvailable(BigDecimal.ZERO);
        buyerBTC.setHold(BigDecimal.ZERO);

        Account buyerUSDT = new Account();
        buyerUSDT.setId("buyer-USDT");
        buyerUSDT.setUserId("buyer");
        buyerUSDT.setCurrency("USDT");
        buyerUSDT.setAvailable(BigDecimal.valueOf(200));
        buyerUSDT.setHold(BigDecimal.ZERO);

        List<Account> snapshotAccounts = List.of(
                sellerBTC,
                sellerUSDT,
                buyerBTC,
                buyerUSDT
        );

        // 5. Restored resting order
        Order restingOrder = new Order();
        restingOrder.setId("resting-sell");
        restingOrder.setSequence(7L);
        restingOrder.setUserId("seller");
        restingOrder.setProductId("BTC-USDT");
        restingOrder.setType(OrderType.LIMIT);
        restingOrder.setSide(OrderSide.SELL);
        restingOrder.setStatus(OrderStatus.OPEN);
        restingOrder.setSize(BigDecimal.valueOf(2));
        restingOrder.setRemainingSize(BigDecimal.valueOf(2));
        restingOrder.setPrice(BigDecimal.valueOf(100));
        restingOrder.setFunds(BigDecimal.valueOf(200));
        restingOrder.setRemainingFunds(BigDecimal.valueOf(200));
        restingOrder.setTime(new Date(0));

        // 6. Make runInSession execute its callback
        doAnswer(invocation -> {
            Consumer<ClientSession> consumer =
                    invocation.getArgument(0);

            consumer.accept(session);
            return null;
        }).when(stateStore).runInSession(any());

        // Configure snapshot reads
        when(stateStore.getEngineState(session))
                .thenReturn(engineState);

        when(stateStore.getProducts(session))
                .thenReturn(List.of(product));

        when(stateStore.getAccounts(session))
                .thenReturn(snapshotAccounts);

        when(stateStore.getOrders(session, "BTC-USDT"))
                .thenReturn(List.of(restingOrder));

        // 7. Constructor now restores everything above
        MatchingEngine matchingEngine =
                new MatchingEngine(stateStore, messageSender);

        assertEquals(
                42L,
                matchingEngine.getStartupCommandOffset()
        );

        verifyNoInteractions(messageSender);

        PlaceOrderCommand buyCommand = new PlaceOrderCommand();
        buyCommand.setOrderId("replay-buy");
        buyCommand.setUserId("buyer");
        buyCommand.setProductId("BTC-USDT");
        buyCommand.setOrderType(OrderType.LIMIT);
        buyCommand.setOrderSide(OrderSide.BUY);
        buyCommand.setSize(BigDecimal.valueOf(2));
        buyCommand.setPrice(BigDecimal.valueOf(100));
        buyCommand.setFunds(BigDecimal.valueOf(200));
        buyCommand.setTime(new Date(1));

        matchingEngine.executeCommand(buyCommand, 43L);

        ArgumentCaptor<Message> messageCaptor =
                ArgumentCaptor.forClass(Message.class);

        verify(messageSender, times(11))
                .send(messageCaptor.capture());

        List<Message> messages = messageCaptor.getAllValues();

        List<Long> expectedMessageSequences = LongStream.rangeClosed(101, 111)
                .boxed()
                .toList();
        List<Long> actualMessageSequences = messages.stream()
                .map(Message::getSequence)
                .toList();
        assertEquals(expectedMessageSequences, actualMessageSequences);

        List<CommandStartMessage> commandStartMessages = messages.stream()
                .filter(CommandStartMessage.class::isInstance)
                .map(CommandStartMessage.class::cast)
                .toList();
        List<CommandEndMessage> commandEndMessages = messages.stream()
                .filter(CommandEndMessage.class::isInstance)
                .map(CommandEndMessage.class::cast)
                .toList();

        assertEquals(1, commandStartMessages.size());
        assertEquals(43L, commandStartMessages.get(0).getCommandOffset());
        assertEquals(1, commandEndMessages.size());
        assertEquals(43L, commandEndMessages.get(0).getCommandOffset());

        List<Trade> trades = messages.stream()
                .filter(TradeMessage.class::isInstance)
                .map(TradeMessage.class::cast)
                .map(TradeMessage::getTrade)
                .toList();

        assertEquals(1, trades.size());
        Trade trade = trades.get(0);
        assertEquals("resting-sell", trade.getMakerOrderId());
        assertEquals("replay-buy", trade.getTakerOrderId());
        assertAmount(2, trade.getSize());
        assertAmount(100, trade.getPrice());
        assertAmount(200, trade.getFunds());
        assertEquals(10L, trade.getSequence());

        List<OrderMessage> orderMessages = messages.stream()
                .filter(OrderMessage.class::isInstance)
                .map(OrderMessage.class::cast)
                .toList();

        assertEquals(3, orderMessages.size());

        OrderMessage buyerReceivedMessage = orderMessages.stream()
                .filter(message -> "replay-buy".equals(message.getOrder().getId()))
                .filter(message -> message.getOrder().getStatus() == OrderStatus.RECEIVED)
                .findFirst()
                .orElseThrow();
        assertEquals(8L, buyerReceivedMessage.getOrder().getSequence());
        assertEquals(11L, buyerReceivedMessage.getOrderBookSequence());

        OrderMessage restoredSellerFilledMessage = orderMessages.stream()
                .filter(message -> "resting-sell".equals(message.getOrder().getId()))
                .filter(message -> message.getOrder().getStatus() == OrderStatus.FILLED)
                .findFirst()
                .orElseThrow();
        assertEquals(7L, restoredSellerFilledMessage.getOrder().getSequence());
        assertEquals(12L, restoredSellerFilledMessage.getOrderBookSequence());

        OrderMessage buyerFilledMessage = orderMessages.stream()
                .filter(message -> "replay-buy".equals(message.getOrder().getId()))
                .filter(message -> message.getOrder().getStatus() == OrderStatus.FILLED)
                .findFirst()
                .orElseThrow();
        assertEquals(8L, buyerFilledMessage.getOrder().getSequence());
        assertEquals(12L, buyerFilledMessage.getOrderBookSequence());

        assertEquals(OrderStatus.FILLED, restingOrder.getStatus());

        assertBalance(buyerBTC, 2, 0);
        assertBalance(buyerUSDT, 0, 0);
        assertBalance(sellerBTC, 0, 0);
        assertBalance(sellerUSDT, 200, 0);

        BigDecimal totalBTC = buyerBTC.getAvailable()
                .add(buyerBTC.getHold())
                .add(sellerBTC.getAvailable())
                .add(sellerBTC.getHold());
        BigDecimal totalUSDT = buyerUSDT.getAvailable()
                .add(buyerUSDT.getHold())
                .add(sellerUSDT.getAvailable())
                .add(sellerUSDT.getHold());

        assertAmount(2, totalBTC);
        assertAmount(200, totalUSDT);
    }

    private static void assertBalance(Account account, long available, long hold) {
        assertAmount(available, account.getAvailable());
        assertAmount(hold, account.getHold());
    }

    private static void assertAmount(long expected, BigDecimal actual) {
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(actual));
    }
}
