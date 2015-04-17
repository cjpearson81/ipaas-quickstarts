/*
 *
 *  * Copyright 2005-2015 Red Hat, Inc.
 *  * Red Hat licenses this file to you under the Apache License, version
 *  * 2.0 (the "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  * implied.  See the License for the specific language governing
 *  * permissions and limitations under the License.
 *
 */

package io.fabric8.mq.controller.coordination;

import io.fabric8.mq.controller.AsyncExecutors;
import io.fabric8.mq.controller.MessageDistribution;
import io.fabric8.mq.controller.coordination.brokers.BrokerModel;
import io.fabric8.mq.controller.coordination.brokers.BrokerTransport;
import io.fabric8.mq.controller.coordination.brokers.DefaultBrokerTransport;
import io.fabric8.mq.controller.model.BrokerControl;
import io.fabric8.mq.controller.model.BrokerModelChangedListener;
import io.fabric8.mq.controller.model.Model;
import io.fabric8.mq.controller.util.MoveDestinationWorker;
import io.fabric8.mq.controller.util.WorkInProgress;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.filter.DestinationMap;
import org.apache.activemq.transport.Transport;
import org.apache.activemq.util.ServiceStopper;
import org.apache.activemq.util.ServiceSupport;
import org.apache.deltaspike.core.api.config.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

public abstract class BaseBrokerControl extends ServiceSupport implements BrokerControl {
    private static final Logger LOG = LoggerFactory.getLogger(BaseBrokerControl.class);
    @Inject
    protected Model model;
    @Inject
    protected AsyncExecutors asyncExecutors;
    protected DestinationMap destinationMap;
    protected List<MessageDistribution> messageDistributionList;
    protected WorkInProgress workInProgress;
    //protected Map<String, BrokerModel> brokerModelMap;
    protected BrokerCoordinator brokerCoordinator;
    protected List<BrokerModelChangedListener> brokerModelChangedListeners;
    private ScheduledFuture poller;
    @Inject
    @ConfigProperty(name = "BROKER_POLL_INTERVAL", defaultValue = "2000")
    private int pollTime;
    @Inject
    @ConfigProperty(name = "BROKER_NAME", defaultValue = "fabric8MQ-AMQ")
    private String brokerName;
    @Inject
    @ConfigProperty(name = "BROKER_GROUP", defaultValue = "mqGroup")
    private String groupName;
    @Inject
    @ConfigProperty(name = "BBROKER_TEMPLATE_LOCATION", defaultValue = "META-INF/replicator-template.json")
    private String brokerTemplateLocation;
    @Inject
    @ConfigProperty(name = "BROKER_SELECTOR", defaultValue = "container=java,component=fabric8MQ,provider=fabric8,group=fabric8MQGroup")
    private String brokerSelector;
    @Inject
    @ConfigProperty(name = "BBROKER_COORDINATOR", defaultValue = "singleton")
    private String brokerCoordinatorType;

    protected BaseBrokerControl() {
        destinationMap = new DestinationMap();
        messageDistributionList = new CopyOnWriteArrayList();
        workInProgress = new WorkInProgress();
        brokerModelChangedListeners = new CopyOnWriteArrayList<>();
    }

    protected abstract void pollBrokers();

    protected abstract void createBroker();

    protected abstract void destroyBroker(BrokerModel brokerModel);

    public String getBrokerSelector() {
        return brokerSelector;
    }

    public void setBrokerSelector(String brokerSelector) {
        this.brokerSelector = brokerSelector;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getBrokerName() {
        return brokerName;
    }

    public void setBrokerName(String brokerName) {
        this.brokerName = brokerName;
    }

    public int getPollTime() {
        return pollTime;
    }

    public void setPollTime(int pollTime) {
        this.pollTime = pollTime;
    }

    public String getBrokerTemplateLocation() {
        return brokerTemplateLocation;
    }

    public void setBrokerTemplateLocation(String brokerTemplateLocation) {
        this.brokerTemplateLocation = brokerTemplateLocation;
    }

    public String getBrokerCoordinatorType() {
        return brokerCoordinatorType;
    }

    public void setBrokerCoordinatorType(String brokerCoordinatorType) {
        this.brokerCoordinatorType = brokerCoordinatorType;
    }

    public Collection<BrokerModel> getBrokerModels() {
        return model.getBrokers();
    }

    @Override
    public Collection<BrokerTransport> getTransports(MessageDistribution messageDistribution) {
        List<BrokerTransport> list = new ArrayList<>();
        for (BrokerModel brokerModel : model.getBrokers()) {
            brokerModel.getReadLock();
            Transport transport = brokerModel.getTransport(messageDistribution);
            list.add(new DefaultBrokerTransport(brokerModel, transport));
        }
        return list;
    }

    @Override
    public BrokerTransport getTransport(MessageDistribution messageDistribution, ActiveMQDestination destination) {
        Set<BrokerModel> set = destinationMap.get(destination);
        if (set != null && !set.isEmpty()) {
            BrokerModel brokerModel = set.iterator().next();
            Transport transport = brokerModel.getTransport(messageDistribution);
            brokerModel.getReadLock();
            return new DefaultBrokerTransport(brokerModel, transport);
        } else {
            //allocate a broker for the destination

            BrokerModel brokerModel = model.getLeastLoadedBroker();
            destinationMap.put(destination, brokerModel);
            Transport transport = brokerModel.getTransport(messageDistribution);
            brokerModel.getReadLock();
            return new DefaultBrokerTransport(brokerModel, transport);
        }
    }

    @Override
    public void addMessageDistribution(MessageDistribution messageDistribution) {
        messageDistributionList.add(messageDistribution);
        if (isStarted()) {
            for (BrokerModel brokerModel : model.getBrokers()) {
                try {
                    brokerModel.createTransport(messageDistribution);
                } catch (Exception e) {
                    LOG.warn("Failed to create transport to " + brokerModel + " for " + messageDistribution);
                }
            }
        }
    }

    @Override
    public void removeMessageDistribution(MessageDistribution messageDistribution) {
        messageDistributionList.remove(messageDistribution);
        for (BrokerModel brokerModel : model.getBrokers()) {
            brokerModel.removeTransport(messageDistribution);
        }
    }

    @Override
    public void addBrokerModelChangedListener(BrokerModelChangedListener brokerModelChangedListener) {
        brokerModelChangedListeners.add(brokerModelChangedListener);
    }

    @Override
    public void removeBrokerModelChangedListener(BrokerModelChangedListener brokerModelChangedListener) {
        brokerModelChangedListeners.remove(brokerModelChangedListener);
    }

    @Override
    protected void doStop(ServiceStopper serviceStopper) throws Exception {
        if (poller != null) {
            poller.cancel(true);
        }
        for (BrokerModel brokerModel : model.getBrokers()) {
            serviceStopper.stop(brokerModel);
        }
        serviceStopper.stop(brokerCoordinator);
    }

    @Override
    protected void doStart() throws Exception {
        setBrokerTemplateLocation(getBrokerTemplateLocation());

        model.start();

        setBrokerCoordinatorType(getBrokerCoordinatorType());
        brokerCoordinator = BrokerCoordinatorFactory.getCoordinator(brokerCoordinatorType);
        brokerCoordinator.start();
        pollBrokers();
        if (model.getBrokerCount() == 0) {
            createBroker();
        }
        Runnable run = new Runnable() {
            @Override
            public void run() {
                try {
                    scheduledTasks();

                } catch (Throwable e) {
                    LOG.error("Failed to validate MQ getLoad: ", e);
                }
            }
        };
        poller = asyncExecutors.scheduleAtFixedRate(run, getPollTime(), getPollTime() * 2);
        for (BrokerModel brokerModel : model.getBrokers()) {
            brokerModel.start();
        }

    }

    private void scheduledTasks() {
        int brokerCount = model.getBrokerCount();
        pollBrokers();
        workInProgress.finished(model.getBrokerCount());
        if (!distributeLoad()) {
            checkScaling();
        }
        if (brokerCount != model.getBrokerCount()) {
            for (BrokerModelChangedListener brokerModelChangedListener : brokerModelChangedListeners) {
                brokerModelChangedListener.brokerNumberChanged(model.getBrokerCount());
            }
        }
    }

    @Override
    public boolean distributeLoad() {
        boolean result = false;
        if (!workInProgress.isWorking()) {
            if (model.getBrokerCount() > 1) {

                final BrokerModel leastLoaded = model.getLeastLoadedBroker();
                final BrokerModel mostLoaded = model.getMostLoadedBroker();

                if ((model.areBrokerLimitsExceeded(mostLoaded) || model.areDestinationLimitsExceeded(mostLoaded)) &&
                        (!model.areBrokerLimitsExceeded(leastLoaded) && !model.areDestinationLimitsExceeded(leastLoaded))) {

                    try {
                        leastLoaded.getWriteLock();
                        mostLoaded.getWriteLock();

                        int maxToCopy = model.getBrokerLimitsConfig().getMaxDestinationsPerBroker() - leastLoaded.getActiveDestinationCount();

                        if (maxToCopy > 0) {
                            List<ActiveMQDestination> copyList = model.getSortedDestinations(mostLoaded, maxToCopy);
                            //check to see we won't break limits
                            if (!copyList.isEmpty()) {
                                copyDestinations(mostLoaded, leastLoaded, copyList);
                                result = true;
                            }
                        }
                    } finally {
                        leastLoaded.unlockWriteLock();
                        mostLoaded.unlockWriteLock();
                    }
                }
            }
        }
        return result;
    }

    private void checkScaling() {
        boolean scaledBack = model.canScaleDownBrokers();
        if (scaledBack) {
            //take destinations from least loaded
            BrokerModel leastLoaded = model.getLeastLoadedBroker();
            if (leastLoaded != null) {
                BrokerModel nextLeastLoaded = model.getNextLeastLoadedBroker(leastLoaded);
                if (nextLeastLoaded != null) {
                    leastLoaded.getWriteLock();
                    nextLeastLoaded.getWriteLock();
                    try {
                        leastLoaded.getWriteLock();
                        nextLeastLoaded.getWriteLock();
                        if (copyDestinations(leastLoaded, nextLeastLoaded)) {
                            destroyBroker(leastLoaded);
                        } else {
                            LOG.error("Scale back failed");
                        }
                    } finally {
                        leastLoaded.unlockWriteLock();
                        nextLeastLoaded.unlockWriteLock();
                    }
                }
            }
        }

        if (!scaledBack) {
            //do we need more brokers ?
            for (BrokerModel brokerModel : model.getBrokers()) {
                if (model.areBrokerLimitsExceeded(brokerModel) || model.areDestinationLimitsExceeded(brokerModel)) {
                    createBroker();
                    break;
                }
            }
        }
    }

    private boolean copyDestinations(BrokerModel from, BrokerModel to) {
        List<ActiveMQDestination> list = new ArrayList<>(from.getActiveDestinations());
        return copyDestinations(from, to, list);
    }

    private boolean copyDestinations(BrokerModel from, BrokerModel to, Collection<ActiveMQDestination> destinations) {
        boolean result = false;
        if (!destinations.isEmpty()) {
            try {
                //move the queues
                MoveDestinationWorker moveDestinationWorker = new MoveDestinationWorker(asyncExecutors, from, to);
                for (ActiveMQDestination destination : destinations) {
                    moveDestinationWorker.addDestinationToCopy(destination);
                    destinationMap.remove(destination, from);
                }
                moveDestinationWorker.start();
                moveDestinationWorker.aWait();
                //update the sharding map
                for (ActiveMQDestination destination : destinations) {
                    destinationMap.put(destination, to);
                }
                result = true;

            } catch (Exception e) {
                LOG.error("Failed in copy from " + from + " to " + to, e);
            }
        } else {
            result = true;
        }
        return result;
    }
}
