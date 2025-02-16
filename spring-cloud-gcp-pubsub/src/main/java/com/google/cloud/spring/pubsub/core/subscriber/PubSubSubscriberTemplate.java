/*
 * Copyright 2017-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spring.pubsub.core.subscriber;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.cloud.spring.pubsub.support.AcknowledgeablePubsubMessage;
import com.google.cloud.spring.pubsub.support.BasicAcknowledgeablePubsubMessage;
import com.google.cloud.spring.pubsub.support.PubSubSubscriptionUtils;
import com.google.cloud.spring.pubsub.support.SubscriberFactory;
import com.google.cloud.spring.pubsub.support.converter.ConvertedAcknowledgeablePubsubMessage;
import com.google.cloud.spring.pubsub.support.converter.ConvertedBasicAcknowledgeablePubsubMessage;
import com.google.cloud.spring.pubsub.support.converter.PubSubMessageConverter;
import com.google.cloud.spring.pubsub.support.converter.SimplePubSubMessageConverter;
import com.google.protobuf.Empty;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.ReceivedMessage;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.Assert;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;

/**
 * Default implementation of {@link PubSubSubscriberOperations}.
 *
 * <p>The main Google Cloud Pub/Sub integration component for consuming
 * messages from subscriptions asynchronously or by pulling.
 *
 * A custom {@link Executor} can be injected to control per-subscription batch
 * parallelization in acknowledgement and deadline operations.
 * By default, this is a single thread executor,
 * created per instance of the {@link PubSubSubscriberTemplate}.
 *
 * A custom {@link Executor} can be injected to control the threads that process
 * the responses of the asynchronous pull callback operations.
 * By default, this is executed on the same thread that executes the callback.
 *
 * @author Vinicius Carvalho
 * @author João André Martins
 * @author Mike Eltsufin
 * @author Chengyuan Zhao
 * @author Doug Hoard
 * @author Elena Felder
 * @author Maurice Zeijen
 *
 * @since 1.1
 */
public class PubSubSubscriberTemplate
		implements PubSubSubscriberOperations, DisposableBean {

	private final SubscriberFactory subscriberFactory;

	private PubSubMessageConverter pubSubMessageConverter = new SimplePubSubMessageConverter();

	private final ExecutorService defaultAckExecutor = Executors.newSingleThreadExecutor();

	private Executor ackExecutor = this.defaultAckExecutor;

	private Executor asyncPullExecutor = Runnable::run;

	private ConcurrentHashMap<String, SubscriberStub> subscriptionNameToStubMap = new ConcurrentHashMap<>();

	/**
	 * Default {@link PubSubSubscriberTemplate} constructor.
	 *
	 * @param subscriberFactory the {@link Subscriber} factory
	 *                          to subscribe to subscriptions or pull messages.
	 */
	public PubSubSubscriberTemplate(SubscriberFactory subscriberFactory) {
		Assert.notNull(subscriberFactory, "The subscriberFactory can't be null.");

		this.subscriberFactory = subscriberFactory;
	}

	/**
	 * Get the converter used to convert a message payload to the desired type.
	 *
	 * @return the currently used converter
	 */
	public PubSubMessageConverter getMessageConverter() {
		return this.pubSubMessageConverter;
	}

	/**
	 * Set the converter used to convert a message payload to the desired type.
	 *
	 * @param pubSubMessageConverter the converter to set
	 */
	public void setMessageConverter(PubSubMessageConverter pubSubMessageConverter) {
		Assert.notNull(pubSubMessageConverter, "The pubSubMessageConverter can't be null.");

		this.pubSubMessageConverter = pubSubMessageConverter;
	}

	/**
	 * Sets the {@link Executor} to control per-subscription batch
	 * parallelization in acknowledgement and deadline operations.
	 *
	 * @param ackExecutor the executor to set
	 */
	public void setAckExecutor(Executor ackExecutor) {
		Assert.notNull(ackExecutor, "ackExecutor can't be null.");
		this.ackExecutor = ackExecutor;
	}

	/**
	 * Set a custom {@link Executor} to control the threads that process
	 * the responses of the asynchronous pull callback operations.
	 *
	 * @param asyncPullExecutor the executor to set
	 */
	public void setAsyncPullExecutor(Executor asyncPullExecutor) {
		Assert.notNull(asyncPullExecutor, "asyncPullExecutor can't be null.");
		this.asyncPullExecutor = asyncPullExecutor;
	}

	@Override
	public Subscriber subscribe(String subscription,
			Consumer<BasicAcknowledgeablePubsubMessage> messageConsumer) {
		Assert.notNull(messageConsumer, "The messageConsumer can't be null.");

		Subscriber subscriber =
				this.subscriberFactory.createSubscriber(subscription,
						(message, ackReplyConsumer) -> messageConsumer.accept(
								new PushedAcknowledgeablePubsubMessage(
										PubSubSubscriptionUtils.toProjectSubscriptionName(subscription,
												this.subscriberFactory.getProjectId()),
										message,
										ackReplyConsumer)));
		subscriber.startAsync();
		return subscriber;
	}

	@Override
	public <T> Subscriber subscribeAndConvert(String subscription,
			Consumer<ConvertedBasicAcknowledgeablePubsubMessage<T>> messageConsumer, Class<T> payloadType) {
		Assert.notNull(messageConsumer, "The messageConsumer can't be null.");

		Subscriber subscriber =
				this.subscriberFactory.createSubscriber(subscription,
						(message, ackReplyConsumer) -> messageConsumer.accept(
								new ConvertedPushedAcknowledgeablePubsubMessage<>(
										PubSubSubscriptionUtils.toProjectSubscriptionName(subscription,
												this.subscriberFactory.getProjectId()),
										message,
										this.getMessageConverter().fromPubSubMessage(message, payloadType),
										ackReplyConsumer)));
		subscriber.startAsync();
		return subscriber;
	}

	/**
	 * Pulls messages synchronously, on demand, using the pull request in argument.
	 *
	 * @param pullRequest pull request containing the subscription name
	 * @return the list of {@link AcknowledgeablePubsubMessage} containing the ack ID, subscription
	 * and acknowledger
	 */
	private List<AcknowledgeablePubsubMessage> pull(PullRequest pullRequest) {
		Assert.notNull(pullRequest, "The pull request can't be null.");
		PullResponse pullResponse = getSubscriberStub(pullRequest.getSubscription()).pullCallable().call(pullRequest);
		return toAcknowledgeablePubsubMessageList(
				pullResponse.getReceivedMessagesList(),
				pullRequest.getSubscription());
	}

	/**
	 * Pulls messages asynchronously, on demand, using the pull request in argument.
	 *
	 * @param pullRequest pull request containing the subscription name
	 * @return the ListenableFuture for the asynchronous execution, returning
	 * the list of {@link AcknowledgeablePubsubMessage} containing the ack ID, subscription
	 * and acknowledger
	 */
	private ListenableFuture<List<AcknowledgeablePubsubMessage>> pullAsync(PullRequest pullRequest) {
		Assert.notNull(pullRequest, "The pull request can't be null.");
		ApiFuture<PullResponse> pullFuture = getSubscriberStub(pullRequest.getSubscription()).pullCallable().futureCall(pullRequest);

		final SettableListenableFuture<List<AcknowledgeablePubsubMessage>> settableFuture = new SettableListenableFuture<>();
		ApiFutures.addCallback(pullFuture, new ApiFutureCallback<PullResponse>() {

			@Override
			public void onFailure(Throwable throwable) {
				settableFuture.setException(throwable);
			}

			@Override
			public void onSuccess(PullResponse pullResponse) {
				List<AcknowledgeablePubsubMessage> result = toAcknowledgeablePubsubMessageList(
						pullResponse.getReceivedMessagesList(), pullRequest.getSubscription());

				settableFuture.set(result);
			}

		}, asyncPullExecutor);

		return settableFuture;
	}

	private List<AcknowledgeablePubsubMessage> toAcknowledgeablePubsubMessageList(List<ReceivedMessage> messages,
			String subscriptionId) {
		return messages.stream()
				.map(message -> new PulledAcknowledgeablePubsubMessage(
						PubSubSubscriptionUtils.toProjectSubscriptionName(subscriptionId,
								this.subscriberFactory.getProjectId()),
						message.getMessage(),
						message.getAckId()))
				.collect(Collectors.toList());
	}

	@Override
	public List<AcknowledgeablePubsubMessage> pull(
			String subscription, Integer maxMessages, Boolean returnImmediately) {
		return pull(this.subscriberFactory.createPullRequest(subscription, maxMessages,
				returnImmediately));
	}

	@Override
	public ListenableFuture<List<AcknowledgeablePubsubMessage>> pullAsync(String subscription, Integer maxMessages, Boolean returnImmediately) {
		return pullAsync(this.subscriberFactory.createPullRequest(subscription, maxMessages, returnImmediately));
	}

	@Override
	public <T> List<ConvertedAcknowledgeablePubsubMessage<T>> pullAndConvert(String subscription, Integer maxMessages,
			Boolean returnImmediately, Class<T> payloadType) {
		List<AcknowledgeablePubsubMessage> ackableMessages = this.pull(subscription, maxMessages, returnImmediately);

		return this.toConvertedAcknowledgeablePubsubMessages(payloadType, ackableMessages);
	}

	@Override
	public <T> ListenableFuture<List<ConvertedAcknowledgeablePubsubMessage<T>>> pullAndConvertAsync(String subscription,
			Integer maxMessages, Boolean returnImmediately, Class<T> payloadType) {
		final SettableListenableFuture<List<ConvertedAcknowledgeablePubsubMessage<T>>> settableFuture = new SettableListenableFuture<>();

		this.pullAsync(subscription, maxMessages, returnImmediately).addCallback(
				ackableMessages -> settableFuture
						.set(this.toConvertedAcknowledgeablePubsubMessages(payloadType, ackableMessages)),
				settableFuture::setException);

		return settableFuture;
	}

	private <T> List<ConvertedAcknowledgeablePubsubMessage<T>> toConvertedAcknowledgeablePubsubMessages(Class<T> payloadType, List<AcknowledgeablePubsubMessage> ackableMessages) {
		return ackableMessages.stream().map(
				m -> new ConvertedPulledAcknowledgeablePubsubMessage<>(m,
						this.pubSubMessageConverter.fromPubSubMessage(m.getPubsubMessage(), payloadType))
		).collect(Collectors.toList());
	}

	@Override
	public List<PubsubMessage> pullAndAck(String subscription, Integer maxMessages,
			Boolean returnImmediately) {
		PullRequest pullRequest = this.subscriberFactory.createPullRequest(
				subscription, maxMessages, returnImmediately);

		List<AcknowledgeablePubsubMessage> ackableMessages = pull(pullRequest);

		if (!ackableMessages.isEmpty()) {
			ack(ackableMessages);
		}

		return ackableMessages.stream().map(AcknowledgeablePubsubMessage::getPubsubMessage)
				.collect(Collectors.toList());
	}

	@Override
	public ListenableFuture<List<PubsubMessage>> pullAndAckAsync(String subscription, Integer maxMessages,
			Boolean returnImmediately) {
		PullRequest pullRequest = this.subscriberFactory.createPullRequest(
				subscription, maxMessages, returnImmediately);

		final SettableListenableFuture<List<PubsubMessage>> settableFuture = new SettableListenableFuture<>();

		this.pullAsync(pullRequest).addCallback(
				ackableMessages -> {
					if (!ackableMessages.isEmpty()) {
						ack(ackableMessages);
					}
					List<PubsubMessage> messages = ackableMessages.stream()
							.map(AcknowledgeablePubsubMessage::getPubsubMessage)
							.collect(Collectors.toList());

					settableFuture.set(messages);
				},
				settableFuture::setException);

		return settableFuture;
	}

	@Override
	public PubsubMessage pullNext(String subscription) {
		List<PubsubMessage> receivedMessageList = pullAndAck(subscription, 1, true);

		return receivedMessageList.isEmpty() ? null : receivedMessageList.get(0);
	}

	@Override
	public ListenableFuture<PubsubMessage> pullNextAsync(String subscription) {
		final SettableListenableFuture<PubsubMessage> settableFuture = new SettableListenableFuture<>();

		this.pullAndAckAsync(subscription, 1, true).addCallback(
				messages -> {
					PubsubMessage message = messages.isEmpty() ? null : messages.get(0);
					settableFuture.set(message);
				},
				settableFuture::setException);

		return settableFuture;
	}

	public SubscriberFactory getSubscriberFactory() {
		return this.subscriberFactory;
	}

	/**
	 * Acknowledge messages in per-subscription batches.
	 * If any batch fails, the returned Future is marked as failed.
	 * If multiple batches fail, the returned Future will contain whichever exception was detected first.
	 * @param acknowledgeablePubsubMessages messages, potentially from different subscriptions.
	 * @return {@link ListenableFuture} indicating overall success or failure.
	 */
	@Override
	public ListenableFuture<Void> ack(
			Collection<? extends AcknowledgeablePubsubMessage> acknowledgeablePubsubMessages) {
		Assert.notEmpty(acknowledgeablePubsubMessages, "The acknowledgeablePubsubMessages can't be empty.");

		return doBatchedAsyncOperation(acknowledgeablePubsubMessages, this::ack);
	}

	/**
	 * Nack messages in per-subscription batches.
	 * If any batch fails, the returned Future is marked as failed.
	 * If multiple batches fail, the returned Future will contain whichever exception was detected first.
	 * @param acknowledgeablePubsubMessages messages, potentially from different subscriptions.
	 * @return {@link ListenableFuture} indicating overall success or failure.
	 */
	@Override
	public ListenableFuture<Void> nack(
			Collection<? extends AcknowledgeablePubsubMessage> acknowledgeablePubsubMessages) {
		return modifyAckDeadline(acknowledgeablePubsubMessages, 0);
	}

	/**
	 * Modify multiple messages' ack deadline in per-subscription batches.
	 * If any batch fails, the returned Future is marked as failed.
	 * If multiple batches fail, the returned Future will contain whichever exception was detected first.
	 * @param acknowledgeablePubsubMessages messages, potentially from different subscriptions.
	 * @return {@link ListenableFuture} indicating overall success or failure.
	 */
	@Override
	public ListenableFuture<Void> modifyAckDeadline(
			Collection<? extends AcknowledgeablePubsubMessage> acknowledgeablePubsubMessages, int ackDeadlineSeconds) {
		Assert.notEmpty(acknowledgeablePubsubMessages, "The acknowledgeablePubsubMessages can't be empty.");
		Assert.isTrue(ackDeadlineSeconds >= 0, "The ackDeadlineSeconds must not be negative.");


		return doBatchedAsyncOperation(acknowledgeablePubsubMessages,
				(String subscriptionName, List<String> ackIds) ->
						modifyAckDeadline(subscriptionName, ackIds, ackDeadlineSeconds));
	}

	/**
	 * Destroys the default executor, regardless of whether it was used.
	 */
	@Override
	public void destroy() {
		this.defaultAckExecutor.shutdown();
		for (SubscriberStub stub : subscriptionNameToStubMap.values()) {
			stub.close();
		}

	}

	private ApiFuture<Empty> ack(String subscriptionName, Collection<String> ackIds) {
		AcknowledgeRequest acknowledgeRequest = AcknowledgeRequest.newBuilder()
				.addAllAckIds(ackIds)
				.setSubscription(subscriptionName)
				.build();
		SubscriberStub subscriberStub = getSubscriberStub(subscriptionName);
		return subscriberStub.acknowledgeCallable().futureCall(acknowledgeRequest);
	}

	private ApiFuture<Empty> modifyAckDeadline(
			String subscriptionName, Collection<String> ackIds, int ackDeadlineSeconds) {
		ModifyAckDeadlineRequest modifyAckDeadlineRequest = ModifyAckDeadlineRequest.newBuilder()
				.setAckDeadlineSeconds(ackDeadlineSeconds)
				.addAllAckIds(ackIds)
				.setSubscription(subscriptionName)
				.build();
		SubscriberStub subscriberStub = getSubscriberStub(subscriptionName);
		return subscriberStub.modifyAckDeadlineCallable().futureCall(modifyAckDeadlineRequest);
	}

	/**
	 * Perform Pub/Sub operations (ack/nack/modifyAckDeadline) in per-subscription batches.
	 * <p>The returned {@link ListenableFuture} will complete when either all batches completes successfully or when at
	 * least one fails.</p>
	 * <p>
	 * In case of multiple batch failures, which exception will be in the final {@link ListenableFuture} is
	 * non-deterministic.
	 * </p>
	 * @param acknowledgeablePubsubMessages messages, could be from different subscriptions.
	 * @param asyncOperation specific Pub/Sub operation to perform.
	 * @return {@link ListenableFuture} indicating overall success or failure.
	 */
	private ListenableFuture<Void> doBatchedAsyncOperation(
			Collection<? extends AcknowledgeablePubsubMessage> acknowledgeablePubsubMessages,
			BiFunction<String, List<String>, ApiFuture<Empty>> asyncOperation) {

		Map<ProjectSubscriptionName, List<String>> groupedMessages
				= acknowledgeablePubsubMessages.stream()
				.collect(
						Collectors.groupingBy(
								AcknowledgeablePubsubMessage::getProjectSubscriptionName,
								Collectors.mapping(AcknowledgeablePubsubMessage::getAckId, Collectors.toList())));

		Assert.state(groupedMessages.keySet().stream().map(ProjectSubscriptionName::getProject).distinct().count() == 1,
				"The project id of all messages must match.");

		SettableListenableFuture<Void> settableListenableFuture	= new SettableListenableFuture<>();
		int numExpectedFutures = groupedMessages.size();
		AtomicInteger numCompletedFutures = new AtomicInteger();

		groupedMessages.forEach((ProjectSubscriptionName psName, List<String> ackIds) -> {

			ApiFuture<Empty> ackApiFuture = asyncOperation.apply(psName.toString(), ackIds);

			ApiFutures.addCallback(ackApiFuture, new ApiFutureCallback<Empty>() {
				@Override
				public void onFailure(Throwable throwable) {
					processResult(throwable);
				}

				@Override
				public void onSuccess(Empty empty) {
					processResult(null);
				}

				private void processResult(Throwable throwable) {
					if (throwable != null) {
						settableListenableFuture.setException(throwable);
					}
					else if (numCompletedFutures.incrementAndGet() == numExpectedFutures) {
						settableListenableFuture.set(null);
					}
				}
			}, this.ackExecutor);
		});

		return settableListenableFuture;
	}

	private SubscriberStub getSubscriberStub(String subscription) {
		if (subscriptionNameToStubMap.containsKey(subscription)) {
			return subscriptionNameToStubMap.get(subscription);
		}
		return subscriptionNameToStubMap.computeIfAbsent(subscription, this.subscriberFactory::createSubscriberStub);
	}

	private abstract static class AbstractBasicAcknowledgeablePubsubMessage
			implements BasicAcknowledgeablePubsubMessage {

		private final ProjectSubscriptionName projectSubscriptionName;

		private final PubsubMessage message;

		AbstractBasicAcknowledgeablePubsubMessage(
				ProjectSubscriptionName projectSubscriptionName, PubsubMessage message) {
			this.projectSubscriptionName = projectSubscriptionName;
			this.message = message;
		}

		@Override
		public ProjectSubscriptionName getProjectSubscriptionName() {
			return this.projectSubscriptionName;
		}

		@Override
		public PubsubMessage getPubsubMessage() {
			return this.message;
		}
	}

	private class PulledAcknowledgeablePubsubMessage extends AbstractBasicAcknowledgeablePubsubMessage
			implements AcknowledgeablePubsubMessage {

		private final String ackId;

		PulledAcknowledgeablePubsubMessage(ProjectSubscriptionName projectSubscriptionName,
				PubsubMessage message, String ackId) {
			super(projectSubscriptionName, message);
			this.ackId = ackId;
		}

		@Override
		public String getAckId() {
			return this.ackId;
		}

		@Override
		public ListenableFuture<Void> ack() {
			return PubSubSubscriberTemplate.this.ack(Collections.singleton(this));
		}

		@Override
		public ListenableFuture<Void> nack() {
			return modifyAckDeadline(0);
		}

		@Override
		public ListenableFuture<Void> modifyAckDeadline(int ackDeadlineSeconds) {
			return PubSubSubscriberTemplate.this.modifyAckDeadline(Collections.singleton(this), ackDeadlineSeconds);
		}

		@Override
		public String toString() {
			return "PulledAcknowledgeablePubsubMessage{" +
					"projectId='" + getProjectSubscriptionName().getProject() + '\'' +
					", subscriptionName='" + getProjectSubscriptionName().getSubscription() + '\'' +
					", message=" + getPubsubMessage() +
					", ackId='" + this.ackId + '\'' +
					'}';
		}
	}

	private static class PushedAcknowledgeablePubsubMessage extends AbstractBasicAcknowledgeablePubsubMessage {

		private final AckReplyConsumer ackReplyConsumer;

		PushedAcknowledgeablePubsubMessage(ProjectSubscriptionName projectSubscriptionName, PubsubMessage message,
				AckReplyConsumer ackReplyConsumer) {
			super(projectSubscriptionName, message);
			this.ackReplyConsumer = ackReplyConsumer;
		}

		@Override
		public ListenableFuture<Void> ack() {
			SettableListenableFuture<Void> settableListenableFuture = new SettableListenableFuture<>();

			try {
				this.ackReplyConsumer.ack();
				settableListenableFuture.set(null);
			}
			catch (Exception e) {
				settableListenableFuture.setException(e);
			}

			return settableListenableFuture;
		}

		@Override
		public ListenableFuture<Void> nack() {
			SettableListenableFuture<Void> settableListenableFuture = new SettableListenableFuture<>();

			try {
				this.ackReplyConsumer.nack();
				settableListenableFuture.set(null);
			}
			catch (Exception e) {
				settableListenableFuture.setException(e);
			}

			return settableListenableFuture;
		}

		@Override
		public String toString() {
			return "PushedAcknowledgeablePubsubMessage{" +
					"projectId='" + getProjectSubscriptionName().getProject() + '\'' +
					", subscriptionName='" + getProjectSubscriptionName().getSubscription() + '\'' +
					", message=" + getPubsubMessage() +
					'}';
		}
	}

	private class ConvertedPulledAcknowledgeablePubsubMessage<T> extends PulledAcknowledgeablePubsubMessage
			implements ConvertedAcknowledgeablePubsubMessage<T> {

		private final T payload;

		ConvertedPulledAcknowledgeablePubsubMessage(AcknowledgeablePubsubMessage message, T payload) {
			super(message.getProjectSubscriptionName(), message.getPubsubMessage(), message.getAckId());

			this.payload = payload;
		}

		@Override
		public T getPayload() {
			return this.payload;
		}
	}

	private static class ConvertedPushedAcknowledgeablePubsubMessage<T> extends PushedAcknowledgeablePubsubMessage
			implements ConvertedBasicAcknowledgeablePubsubMessage<T> {

		private final T payload;

		ConvertedPushedAcknowledgeablePubsubMessage(ProjectSubscriptionName projectSubscriptionName,
				PubsubMessage message, T payload, AckReplyConsumer ackReplyConsumer) {
			super(projectSubscriptionName, message, ackReplyConsumer);
			this.payload = payload;
		}

		@Override
		public T getPayload() {
			return this.payload;
		}
	}

}
