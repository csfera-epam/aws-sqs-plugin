/*
 * Copyright 2016 M-Way Solutions GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.relution.jenkins.awssqs.threading;

import com.amazonaws.services.sqs.model.Message;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import io.relution.jenkins.awssqs.interfaces.SQSQueueMonitor;
import io.relution.jenkins.awssqs.logging.Log;
import io.relution.jenkins.awssqs.net.SQSChannel;


public class SQSQueueMonitorImpl implements SQSQueueMonitor {

    private final static String          ERROR_WRONG_QUEUE = "The specified listener is associated with another queue.";

    private final ExecutorService        executor;

    private final io.relution.jenkins.awssqs.interfaces.SQSQueue queue;
    private final SQSChannel             channel;

    private final Object                 listenersLock     = new Object();
    private final List<io.relution.jenkins.awssqs.interfaces.SQSQueueListener> listeners;

    private final AtomicBoolean          isRunning         = new AtomicBoolean();
    private volatile boolean             isShutDown;
    private final Jenkins                jenkins           = Jenkins.getInstance();

    public SQSQueueMonitorImpl(final ExecutorService executor, final io.relution.jenkins.awssqs.interfaces.SQSQueue queue, final SQSChannel channel) {
        io.relution.jenkins.awssqs.util.ThrowIf.isNull(executor, "executor");
        io.relution.jenkins.awssqs.util.ThrowIf.isNull(channel, "channel");

        this.executor = executor;

        this.queue = queue;
        this.channel = channel;

        this.listeners = new ArrayList<>();
    }

    private SQSQueueMonitorImpl(final ExecutorService executor,
            final io.relution.jenkins.awssqs.interfaces.SQSQueue queue,
            final SQSChannel channel,
            final List<io.relution.jenkins.awssqs.interfaces.SQSQueueListener> listeners) {
        io.relution.jenkins.awssqs.util.ThrowIf.isNull(executor, "executor");
        io.relution.jenkins.awssqs.util.ThrowIf.isNull(channel, "channel");

        this.executor = executor;

        this.queue = queue;
        this.channel = channel;

        this.listeners = listeners;
    }

    @Override
    public SQSQueueMonitor clone(final io.relution.jenkins.awssqs.interfaces.SQSQueue queue, final SQSChannel channel) {
        synchronized (this.listenersLock) {
            return new SQSQueueMonitorImpl(this.executor, queue, channel, this.listeners);
        }
    }

    @Override
    public boolean add(final io.relution.jenkins.awssqs.interfaces.SQSQueueListener listener) {
        io.relution.jenkins.awssqs.util.ThrowIf.isNull(listener, "listener");
        io.relution.jenkins.awssqs.util.ThrowIf.notEqual(listener.getQueueUuid(), this.channel.getQueueUuid(), ERROR_WRONG_QUEUE);

        synchronized (this.listenersLock) {
            if (this.listeners.add(listener) && this.listeners.size() == 1) {
                this.isShutDown = false;
                this.execute();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean remove(final io.relution.jenkins.awssqs.interfaces.SQSQueueListener listener) {
        if (listener == null) {
            return false;
        }

        synchronized (this.listenersLock) {
            if (this.listeners.remove(listener) && this.listeners.isEmpty()) {
                this.shutDown();
                return true;
            }
        }
        return false;
    }

    @Override
    public void run() {
        try {
            if (this.isShutDown) {
                return;
            }

            if (!this.isRunning.compareAndSet(false, true)) {
                Log.warning("Monitor for %s already started", this.channel);
                return;
            }

            Log.fine("Start synchronous monitor for %s", this.channel);

            if (this.isQuietingDown()) {
                Log.info("Skipping %s since Jenkins is preparing for shutdown", this.channel);
                Thread.sleep(15000);
                return;
            }

            final int currentJobQueueSize = this.countBuildableItems();
            final int maxJobQueueSize = this.queue.getMaxNumberOfJobQueue();
            if (currentJobQueueSize > maxJobQueueSize) {
                Log.info("Skipping %s - Jenkins build queue %d is greater than configured %d", this.channel, currentJobQueueSize, maxJobQueueSize);
                Thread.sleep(15000);
                return;
            }
            this.processMessages();
        } catch (final com.amazonaws.services.sqs.model.QueueDoesNotExistException e) {
            Log.warning("Queue %s does not exist, monitor stopped", this.channel);
            this.isShutDown = true;

        } catch (final com.amazonaws.AmazonServiceException e) {
            Log.warning("Service error for queue %s, monitor stopped", this.channel);
            this.isShutDown = true;

        } catch (final Exception e) {
            Log.severe(e, "Unknown error, monitor for queue %s stopped", this.channel);
            this.isShutDown = true;

        } finally {
            if (!this.isRunning.compareAndSet(true, false)) {
                Log.warning("Monitor for %s already stopped", this.channel);
            }
            this.execute();
        }
    }

    @Override
    public void shutDown() {
        Log.info("Shut down monitor for %s", this.channel);
        this.isShutDown = true;
    }

    @Override
    public boolean isShutDown() {
        return this.isShutDown;
    }

    @Override
    public io.relution.jenkins.awssqs.interfaces.SQSQueue getQueue() {
        return this.queue;
    }

    @Override
    public SQSChannel getChannel() {
        return this.channel;
    }

    private void execute() {
        if (!this.isShutDown) {
            this.executor.execute(this);
        }
    }

    private boolean isQuietingDown() {
        return (jenkins != null ? jenkins.isQuietingDown() : false);
    }

    private int countBuildableItems() {
        return (jenkins != null ? jenkins.getQueue().countBuildableItems() : 0);
    }

    private void processMessages() {
        final List<Message> messages = this.channel.getMessages();

        if (this.isShutDown) {
            return;
        }

        if (this.notifyListeners(messages) && !this.queue.isKeepQueueMessages()) {
            this.channel.deleteMessages(messages);
        } else if (!messages.isEmpty() && this.queue.isKeepQueueMessages()) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
               Log.warning("Exception %s occurred while sleeping after message retrieval on %s", ex.toString(), this.channel);
            }
        }
    }

    private boolean notifyListeners(final List<Message> messages) {
        if (messages.isEmpty()) {
            Log.fine("Received no messages from %s", this.channel);
            return false;
        }

        Log.info("Received %d message(s) from %s", messages.size(), this.channel);
        final List<io.relution.jenkins.awssqs.interfaces.SQSQueueListener> listeners = this.getListeners();

        for (final io.relution.jenkins.awssqs.interfaces.SQSQueueListener listener : listeners) {
            listener.handleMessages(messages);
        }

        return true;
    }

    private List<io.relution.jenkins.awssqs.interfaces.SQSQueueListener> getListeners() {
        synchronized (this.listenersLock) {
            return new ArrayList<>(this.listeners);
        }
    }
}
