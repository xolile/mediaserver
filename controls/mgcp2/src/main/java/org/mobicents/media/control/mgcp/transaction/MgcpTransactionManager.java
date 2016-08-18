/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc and individual contributors
 * by the @authors tag. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.media.control.mgcp.transaction;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.log4j.Logger;
import org.mobicents.media.control.mgcp.command.MgcpCommand;
import org.mobicents.media.control.mgcp.command.MgcpCommandResult;
import org.mobicents.media.control.mgcp.exception.DuplicateMgcpTransactionException;
import org.mobicents.media.control.mgcp.exception.MgcpTransactionNotFoundException;
import org.mobicents.media.control.mgcp.message.MessageDirection;
import org.mobicents.media.control.mgcp.message.MgcpMessage;
import org.mobicents.media.control.mgcp.message.MgcpMessageObserver;
import org.mobicents.media.control.mgcp.message.MgcpParameterType;
import org.mobicents.media.control.mgcp.message.MgcpRequest;
import org.mobicents.media.control.mgcp.message.MgcpResponse;
import org.mobicents.media.control.mgcp.util.collections.Parameters;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

/**
 * Manages a group of MGCP transactions.
 * 
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class MgcpTransactionManager implements TransactionManager {

    private static final Logger log = Logger.getLogger(MgcpTransactionManager.class);

    // Concurrency Components
    private final ListeningExecutorService executor;
    private final MgcpCommandCallback commandCallback;

    // MGCP Components
    private final MgcpTransactionProvider transactionProvider;

    // MGCP Transaction Manager
    private final ConcurrentHashMap<Integer, MgcpTransaction> transactions;
    /*
     * TODO MGCP entities MUST keep in memory a list of the responses that they sent to recent transactions, i.e., a list of all
     * the responses they sent over the last T-HIST seconds.
     * 
     * The transaction identifiers of incoming commands are compared to the transaction identifiers of the recent responses. If
     * a match is found, the MGCP entity does not execute the transaction, but simply repeats the response.
     */

    // Observers
    private final Collection<MgcpMessageObserver> observers;

    public MgcpTransactionManager(MgcpTransactionProvider transactionProvider, ListeningExecutorService executor) {
        // Concurrency Components
        this.executor = executor;
        this.commandCallback = new MgcpCommandCallback();

        // MGCP Components
        this.transactionProvider = transactionProvider;

        // MGCP Transaction Manager
        this.transactions = new ConcurrentHashMap<>(500);

        // Observers
        this.observers = new CopyOnWriteArrayList<>();
    }

    private MgcpTransaction createTransaction(MgcpRequest request) throws DuplicateMgcpTransactionException {
        int transactionId = request.getTransactionId();

        // Create Transaction
        MgcpTransaction transaction;
        if (transactionId == 0) {
            // Transaction originated from within this Media Server (NTFY, for example)
            // to be sent out to call agent. A transaction ID must be generated
            transaction = this.transactionProvider.provideLocal();

            // Patch transaction ID
            request.setTransactionId(transaction.getId());
            transactionId = transaction.getId();
        } else {
            // Transaction originated from the remote call agent
            transaction = this.transactionProvider.provideRemote(transactionId);
        }

        // Register Transaction
        MgcpTransaction old = this.transactions.putIfAbsent(transaction.getId(), transaction);

        // Ensure transaction is not duplicate
        if (old != null) {
            throw new DuplicateMgcpTransactionException("Transaction " + transactionId + " already exists.");
        }

        if (log.isDebugEnabled()) {
            log.debug("Created new transaction " + transactionId + " to process request");
        }

        return transaction;
    }

    boolean contains(int transactionId) {
        return this.transactions.containsKey(transactionId);
    }

    @Override
    public void process(MgcpRequest request, MgcpCommand command) throws DuplicateMgcpTransactionException {
        createTransaction(request);
        if (command != null) {
            ListenableFuture<MgcpCommandResult> future = this.executor.submit(command);
            Futures.addCallback(future, this.commandCallback);
        }
    }

    @Override
    public void process(MgcpResponse response) throws MgcpTransactionNotFoundException {
        MgcpTransaction transaction = this.transactions.remove(response.getTransactionId());
        if (transaction == null) {
            throw new MgcpTransactionNotFoundException("Could not find transaction " + response.getTransactionId());
        } else if (log.isDebugEnabled()) {
            log.debug("Closed transaction " + response.getTransactionId() + " with code " + response.getCode());
        }
    }

    @Override
    public void observe(MgcpMessageObserver observer) {
        this.observers.add(observer);
    }

    @Override
    public void forget(MgcpMessageObserver observer) {
        this.observers.remove(observer);
    }

    @Override
    public void notify(Object originator, MgcpMessage message, MessageDirection direction) {
        Iterator<MgcpMessageObserver> iterator = this.observers.iterator();
        while (iterator.hasNext()) {
            MgcpMessageObserver observer = iterator.next();
            if (observer != originator) {
                observer.onMessage(message, direction);
            }
        }
    }

    /**
     * Handles MGCP command responses after their execution.
     * 
     * @author Henrique Rosa (henrique.rosa@telestax.com)
     *
     */
    private final class MgcpCommandCallback implements FutureCallback<MgcpCommandResult> {

        @Override
        public void onSuccess(MgcpCommandResult result) {
            MgcpResponse response = buildResponse(result);
            MgcpTransactionManager.this.notify(MgcpTransactionManager.this, response, MessageDirection.OUTGOING);
        }

        @Override
        public void onFailure(Throwable t) {
            // TODO Auto-generated method stub
            log.error("An error occurred processing an MGCP Command.", t);
        }
        
        private MgcpResponse buildResponse(MgcpCommandResult result) {
            MgcpResponse response = new MgcpResponse();
            response.setCode(result.getCode());
            response.setMessage(result.getMessage());
            response.setTransactionId(result.getTransactionId());
            
            Parameters<MgcpParameterType> parameters = result.getParameters();
            if(parameters.size() > 0) {
                Iterator<MgcpParameterType> iterator = parameters.keySet().iterator();
                while (iterator.hasNext()) {
                    MgcpParameterType key = iterator.next();
                    Optional<String> value = parameters.getString(key);
                    if(value.isPresent()) {
                        response.addParameter(key, value.get());
                    }
                }
            }
            
            return response;
        }

    }

}