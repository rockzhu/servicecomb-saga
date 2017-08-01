/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.saga.core;

import static io.servicecomb.saga.core.Compensation.SAGA_END_COMPENSATION;
import static io.servicecomb.saga.core.Compensation.SAGA_START_COMPENSATION;
import static io.servicecomb.saga.core.SagaEventMatcher.eventWith;
import static io.servicecomb.saga.core.Transaction.SAGA_END_TRANSACTION;
import static io.servicecomb.saga.core.Transaction.SAGA_START_TRANSACTION;
import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.servicecomb.saga.core.dag.Node;
import io.servicecomb.saga.core.dag.SingleLeafDirectedAcyclicGraph;
import io.servicecomb.saga.infrastructure.EmbeddedEventStore;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;

public class SagaIntegrationTest {

  private final IdGenerator<Long> idGenerator = new LongIdGenerator();
  private final EventStore eventStore = new EmbeddedEventStore();

  private final Transaction transaction1 = mock(Transaction.class, "transaction1");
  private final Transaction transaction2 = mock(Transaction.class, "transaction2");
  private final Transaction transaction3 = mock(Transaction.class, "transaction3");

  private final Transaction[] transactions = {transaction1, transaction2};

  private final Compensation compensation1 = mock(Compensation.class, "compensation1");
  private final Compensation compensation2 = mock(Compensation.class, "compensation2");
  private final Compensation compensation3 = mock(Compensation.class, "compensation3");

  private final Compensation[] compensations = {compensation1, compensation2};

  private final SagaRequest sagaStartRequest = new SagaRequestImpl("saga-start", SAGA_START_TRANSACTION, SAGA_START_COMPENSATION, new SagaStartTask(eventStore));
  private final SagaRequest request1 = new SagaRequestImpl("request1", transaction1, compensation1, new RequestProcessTask(eventStore));
  private final SagaRequest request2 = new SagaRequestImpl("request2", transaction2, compensation2, new RequestProcessTask(eventStore));
  private final SagaRequest request3 = new SagaRequestImpl("request3", transaction3, compensation3, new RequestProcessTask(eventStore));
  private final SagaRequest sagaEndRequest = new SagaRequestImpl("saga-end", SAGA_END_TRANSACTION, SAGA_END_COMPENSATION, new SagaEndTask(eventStore));

  private final RuntimeException exception = new RuntimeException("oops");

  private final Node<SagaRequest> node1 = new Node<>(1, request1);
  private final Node<SagaRequest> node2 = new Node<>(2, request2);
  private final Node<SagaRequest> node3 = new Node<>(3, request3);
  private final Node<SagaRequest> root = new Node<>(0, sagaStartRequest);
  private final Node<SagaRequest> leaf = new Node<>(4, sagaEndRequest);
  private final SingleLeafDirectedAcyclicGraph<SagaRequest> sagaTaskGraph = new SingleLeafDirectedAcyclicGraph<>(root, leaf);

  private Saga saga;

  // root - node1 - node2 - leaf
  @Before
  public void setUp() throws Exception {
    root.addChild(node1);
    node1.addChild(node2);
    node2.addChild(leaf);

    saga = new Saga(eventStore, sagaTaskGraph);
  }

  @Test
  public void transactionsAreRunSuccessfully() {
    saga.run();

    assertThat(eventStore, contains(
        eventWith(1L, SAGA_START_TRANSACTION, SagaStartedEvent.class),
        eventWith(2L, transaction1, TransactionStartedEvent.class),
        eventWith(3L, transaction1, TransactionEndedEvent.class),
        eventWith(4L, transaction2, TransactionStartedEvent.class),
        eventWith(5L, transaction2, TransactionEndedEvent.class),
        eventWith(6L, SAGA_END_TRANSACTION, SagaEndedEvent.class)
    ));

    for (Transaction transaction : transactions) {
      verify(transaction).run();
    }

    for (Compensation compensation : compensations) {
      verify(compensation, never()).run();
    }
  }

  // root - node1 - node2 - leaf
  //             \_ node3 _/
  @Test
  public void compensateCommittedTransactionsOnFailure() {
    addExtraChildToNode1();

    // barrier to make sure the two transactions starts at the same time
    CyclicBarrier barrier = new CyclicBarrier(2);
    doAnswer(withAnswer(() -> {
      barrier.await();
      Thread.sleep(100);
      throw exception;
    })).when(transaction2).run();

    doAnswer(withAnswer(() -> {
      barrier.await();
      return null;
    })).when(transaction3).run();

    saga.run();

    assertThat(eventStore, contains(
        eventWith(1L, SAGA_START_TRANSACTION, SagaStartedEvent.class),
        eventWith(2L, transaction1, TransactionStartedEvent.class),
        eventWith(3L, transaction1, TransactionEndedEvent.class),
        anyOf(eventWith(4L, transaction2, TransactionStartedEvent.class), eventWith(4L, transaction3, TransactionStartedEvent.class)),
        anyOf(eventWith(5L, transaction2, TransactionStartedEvent.class), eventWith(5L, transaction3, TransactionStartedEvent.class)),
        eventWith(6L, transaction3, TransactionEndedEvent.class),
        eventWith(7L, transaction2, TransactionAbortedEvent.class),
        eventWith(8L, compensation3, CompensationStartedEvent.class),
        eventWith(9L, compensation3, CompensationEndedEvent.class),
        eventWith(10L, compensation1, CompensationStartedEvent.class),
        eventWith(11L, compensation1, CompensationEndedEvent.class),
        eventWith(12L, SAGA_START_COMPENSATION, SagaEndedEvent.class)));

    verify(transaction1).run();
    verify(transaction2).run();
    verify(transaction3).run();

    verify(compensation1).run();
    verify(compensation2, never()).run();
    verify(compensation3).run();
  }

  // root - node1 - node2 - leaf
  //             \_ node4 _/
  @Test
  public void redoHangingTransactionsOnFailure() throws InterruptedException {
    addExtraChildToNode1();

    // barrier to make sure the two transactions starts at the same time
    CyclicBarrier barrier = new CyclicBarrier(2);
    doAnswer(withAnswer(() -> {
      barrier.await();
      throw exception;
    })).when(transaction3).run();

    CountDownLatch latch = new CountDownLatch(1);

    doAnswer(withAnswer(() -> {
      barrier.await();
      latch.await();
      return null;
    })).doNothing().when(transaction2).run();

    saga.run();

    // the ordering of events may not be consistence due to concurrent processing of requests
    assertThat(eventStore, contains(
        eventWith(1L, SAGA_START_TRANSACTION, SagaStartedEvent.class),
        eventWith(2L, transaction1, TransactionStartedEvent.class),
        eventWith(3L, transaction1, TransactionEndedEvent.class),
        anyOf(
            eventWith(4L, transaction2, TransactionStartedEvent.class),
            eventWith(4L, transaction3, TransactionStartedEvent.class)),
        anyOf(
            eventWith(5L, transaction3, TransactionStartedEvent.class),
            eventWith(5L, transaction2, TransactionStartedEvent.class)),
        eventWith(6L, transaction3, TransactionAbortedEvent.class),
        eventWith(7L, transaction2, TransactionStartedEvent.class),
        eventWith(8L, transaction2, TransactionEndedEvent.class),
        eventWith(9L, compensation2, CompensationStartedEvent.class),
        eventWith(10L, compensation2, CompensationEndedEvent.class),
        eventWith(11L, compensation1, CompensationStartedEvent.class),
        eventWith(12L, compensation1, CompensationEndedEvent.class),
        eventWith(13L, SAGA_START_COMPENSATION, SagaEndedEvent.class)));

    verify(transaction1).run();
    verify(transaction2, times(2)).run();
    verify(transaction3).run();

    verify(compensation1).run();
    verify(compensation2).run();
    verify(compensation3, never()).run();

    latch.countDown();
  }

  @Test
  public void retriesFailedTransactionTillSuccess() {
    Saga saga = new Saga(eventStore, new ForwardRecovery(), sagaTaskGraph);

    doThrow(exception).doThrow(exception).doNothing().when(transaction2).run();

    saga.run();

    assertThat(eventStore, contains(
        eventWith(1L, SAGA_START_TRANSACTION, SagaStartedEvent.class),
        eventWith(2L, transaction1, TransactionStartedEvent.class),
        eventWith(3L, transaction1, TransactionEndedEvent.class),
        eventWith(4L, transaction2, TransactionStartedEvent.class),
        eventWith(5L, transaction2, TransactionStartedEvent.class),
        eventWith(6L, transaction2, TransactionStartedEvent.class),
        eventWith(7L, transaction2, TransactionEndedEvent.class),
        eventWith(8L, SAGA_END_TRANSACTION, SagaEndedEvent.class)
    ));

    for (Transaction transaction : transactions) {
      verify(transaction, atLeastOnce()).run();
    }

    for (Compensation compensation : compensations) {
      verify(compensation, never()).run();
    }

    verify(transaction2, times(3)).run();
  }

  @Test
  public void restoresSagaToTransactionStateByPlayingAllEvents() {
    addExtraChildToNode1();

    Iterable<EventEnvelope> events = asList(
        envelope(new SagaStartedEvent(sagaStartRequest)),
        envelope(new TransactionStartedEvent(request1)),
        envelope(new TransactionEndedEvent(request1)),
        envelope(new TransactionStartedEvent(request2)),
        envelope(new TransactionEndedEvent(request2))
    );

    eventStore.populate(events);
    saga.play();

    saga.run();
    assertThat(eventStore, contains(
        eventWith(1L, SAGA_START_TRANSACTION, SagaStartedEvent.class),
        eventWith(2L, transaction1, TransactionStartedEvent.class),
        eventWith(3L, transaction1, TransactionEndedEvent.class),
        eventWith(4L, transaction2, TransactionStartedEvent.class),
        eventWith(5L, transaction2, TransactionEndedEvent.class),
        eventWith(6L, transaction3, TransactionStartedEvent.class),
        eventWith(7L, transaction3, TransactionEndedEvent.class),
        eventWith(8L, SAGA_END_TRANSACTION, SagaEndedEvent.class)
    ));

    verify(transaction1, never()).run();
    verify(transaction2, never()).run();
    verify(transaction3).run();

    for (Compensation compensation : compensations) {
      verify(compensation, never()).run();
    }
  }

  @Test
  public void restoresPartialTransactionByPlayingAllEvents() {
    addExtraChildToNode1();

    Iterable<EventEnvelope> events = asList(
        envelope(new SagaStartedEvent(sagaStartRequest)),
        envelope(new TransactionStartedEvent(request1)),
        envelope(new TransactionEndedEvent(request1)),
        envelope(new TransactionStartedEvent(request2)),
        envelope(new TransactionEndedEvent(request2)),
        envelope(new TransactionStartedEvent(request3))
    );

    eventStore.populate(events);
    saga.play();

    saga.run();
    assertThat(eventStore, contains(
        eventWith(1L, SAGA_START_TRANSACTION, SagaStartedEvent.class),
        eventWith(2L, transaction1, TransactionStartedEvent.class),
        eventWith(3L, transaction1, TransactionEndedEvent.class),
        eventWith(4L, transaction2, TransactionStartedEvent.class),
        eventWith(5L, transaction2, TransactionEndedEvent.class),
        eventWith(6L, transaction3, TransactionStartedEvent.class),
        eventWith(7L, transaction3, TransactionStartedEvent.class),
        eventWith(8L, transaction3, TransactionEndedEvent.class),
        eventWith(9L, SAGA_END_TRANSACTION, SagaEndedEvent.class)
    ));

    verify(transaction1, never()).run();
    verify(transaction2, never()).run();
    verify(transaction3).run();

    for (Compensation compensation : compensations) {
      verify(compensation, never()).run();
    }
  }

  @Test
  public void restoresToCompensationFromAbortedTransactionByPlayingAllEvents() {
    addExtraChildToNode1();

    Iterable<EventEnvelope> events = asList(
        envelope(new SagaStartedEvent(sagaStartRequest)),
        envelope(new TransactionStartedEvent(request1)),
        envelope(new TransactionEndedEvent(request1)),
        envelope(new TransactionStartedEvent(request2)),
        envelope(new TransactionEndedEvent(request2)),
        envelope(new TransactionStartedEvent(request3)),
        envelope(new TransactionAbortedEvent(request3, exception))
    );

    eventStore.populate(events);
    saga.play();

    saga.run();
    assertThat(eventStore, contains(
        eventWith(1L, SAGA_START_TRANSACTION, SagaStartedEvent.class),
        eventWith(2L, transaction1, TransactionStartedEvent.class),
        eventWith(3L, transaction1, TransactionEndedEvent.class),
        eventWith(4L, transaction2, TransactionStartedEvent.class),
        eventWith(5L, transaction2, TransactionEndedEvent.class),
        eventWith(6L, transaction3, TransactionStartedEvent.class),
        eventWith(7L, transaction3, TransactionAbortedEvent.class),
        eventWith(8L, compensation2, CompensationStartedEvent.class),
        eventWith(9L, compensation2, CompensationEndedEvent.class),
        eventWith(10L, compensation1, CompensationStartedEvent.class),
        eventWith(11L, compensation1, CompensationEndedEvent.class),
        eventWith(12L, SAGA_START_COMPENSATION, SagaEndedEvent.class)
    ));

    verify(transaction1, never()).run();
    verify(transaction2, never()).run();
    verify(transaction3, never()).run();

    for (Compensation compensation : compensations) {
      verify(compensation).run();
    }
    verify(compensation3, never()).run();
  }

  @Test
  public void restoresSagaToCompensationStateByPlayingAllEvents() {
    addExtraChildToNode1();

    Iterable<EventEnvelope> events = asList(
        envelope(new SagaStartedEvent(sagaStartRequest)),
        envelope(new TransactionStartedEvent(request1)),
        envelope(new TransactionEndedEvent(request1)),
        envelope(new TransactionStartedEvent(request2)),
        envelope(new TransactionEndedEvent(request2)),
        envelope(new TransactionStartedEvent(request3)),
        envelope(new TransactionEndedEvent(request3)),
        envelope(new CompensationStartedEvent(request2)),
        envelope(new CompensationEndedEvent(request2))
    );

    eventStore.populate(events);
    saga.play();

    saga.run();
    assertThat(eventStore, contains(
        eventWith(1L, SAGA_START_TRANSACTION, SagaStartedEvent.class),
        eventWith(2L, transaction1, TransactionStartedEvent.class),
        eventWith(3L, transaction1, TransactionEndedEvent.class),
        eventWith(4L, transaction2, TransactionStartedEvent.class),
        eventWith(5L, transaction2, TransactionEndedEvent.class),
        eventWith(6L, transaction3, TransactionStartedEvent.class),
        eventWith(7L, transaction3, TransactionEndedEvent.class),
        eventWith(8L, compensation2, CompensationStartedEvent.class),
        eventWith(9L, compensation2, CompensationEndedEvent.class),
        eventWith(10L, compensation3, CompensationStartedEvent.class),
        eventWith(11L, compensation3, CompensationEndedEvent.class),
        eventWith(12L, compensation1, CompensationStartedEvent.class),
        eventWith(13L, compensation1, CompensationEndedEvent.class),
        eventWith(14L, SAGA_START_COMPENSATION, SagaEndedEvent.class)
    ));

    verify(transaction1, never()).run();
    verify(transaction2, never()).run();
    verify(transaction3, never()).run();

    verify(compensation1).run();
    verify(compensation2, never()).run();
    verify(compensation3).run();
  }

  @Test
  public void restoresPartialCompensationByPlayingAllEvents() {
    addExtraChildToNode1();

    Iterable<EventEnvelope> events = asList(
        envelope(new SagaStartedEvent(sagaStartRequest)),
        envelope(new TransactionStartedEvent(request1)),
        envelope(new TransactionEndedEvent(request1)),
        envelope(new TransactionStartedEvent(request2)),
        envelope(new TransactionEndedEvent(request2)),
        envelope(new TransactionStartedEvent(request3)),
        envelope(new TransactionEndedEvent(request3)),
        envelope(new CompensationStartedEvent(request2)),
        envelope(new CompensationEndedEvent(request2)),
        envelope(new CompensationStartedEvent(request3))
    );

    eventStore.populate(events);
    saga.play();

    saga.run();
    assertThat(eventStore, contains(
        eventWith(1L, SAGA_START_TRANSACTION, SagaStartedEvent.class),
        eventWith(2L, transaction1, TransactionStartedEvent.class),
        eventWith(3L, transaction1, TransactionEndedEvent.class),
        eventWith(4L, transaction2, TransactionStartedEvent.class),
        eventWith(5L, transaction2, TransactionEndedEvent.class),
        eventWith(6L, transaction3, TransactionStartedEvent.class),
        eventWith(7L, transaction3, TransactionEndedEvent.class),
        eventWith(8L, compensation2, CompensationStartedEvent.class),
        eventWith(9L, compensation2, CompensationEndedEvent.class),
        eventWith(10L, compensation3, CompensationStartedEvent.class),
        eventWith(11L, compensation3, CompensationStartedEvent.class),
        eventWith(12L, compensation3, CompensationEndedEvent.class),
        eventWith(13L, compensation1, CompensationStartedEvent.class),
        eventWith(14L, compensation1, CompensationEndedEvent.class),
        eventWith(15L, SAGA_START_COMPENSATION, SagaEndedEvent.class)
    ));

    verify(transaction1, never()).run();
    verify(transaction2, never()).run();
    verify(transaction3, never()).run();

    verify(compensation1).run();
    verify(compensation2, never()).run();
    verify(compensation3).run();
  }

  @Test
  public void restoresSagaToEndStateByPlayingAllEvents() {
    Iterable<EventEnvelope> events = asList(
        envelope(new SagaStartedEvent(sagaStartRequest)),
        envelope(new TransactionStartedEvent(request1)),
        envelope(new TransactionEndedEvent(request1)),
        envelope(new TransactionStartedEvent(request2)),
        envelope(new TransactionEndedEvent(request2))
    );

    eventStore.populate(events);
    saga.play();

    saga.run();
    assertThat(eventStore, contains(
        eventWith(1L, SAGA_START_TRANSACTION, SagaStartedEvent.class),
        eventWith(2L, transaction1, TransactionStartedEvent.class),
        eventWith(3L, transaction1, TransactionEndedEvent.class),
        eventWith(4L, transaction2, TransactionStartedEvent.class),
        eventWith(5L, transaction2, TransactionEndedEvent.class),
        eventWith(6L, SAGA_END_TRANSACTION, SagaEndedEvent.class)
    ));

    for (Transaction transaction : transactions) {
      verify(transaction, never()).run();
    }

    for (Compensation compensation : compensations) {
      verify(compensation, never()).run();
    }
  }

  private Answer<Void> withAnswer(Callable<Void> callable) {
    return invocationOnMock -> callable.call();
  }

  private EventEnvelope envelope(SagaEvent event) {
    return new EventEnvelope(idGenerator.nextId(), event);
  }

  private void addExtraChildToNode1() {
    node1.addChild(node3);
    node3.addChild(leaf);
  }
}
