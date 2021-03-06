package hudson.plugins.spotinst.jobs;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.plugins.spotinst.SpotinstSlave;
import hudson.plugins.spotinst.common.ContextInstance;
import hudson.plugins.spotinst.common.SpotinstContext;
import hudson.plugins.spotinst.common.SpotinstGateway;
import hudson.plugins.spotinst.spot.SpotRequest;
import jenkins.model.Jenkins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Created by ohadmuchnik on 25/05/2016.
 */
@Extension
public class SpotinstInstancesMonitor extends AsyncPeriodicWork {

    //region Members
    private static final Logger LOGGER = LoggerFactory.getLogger(SpotinstInstancesMonitor.class);
    private static final Integer TIMEOUT = 10;
    final long recurrencePeriod;
    //endregion

    //region Constructor
    public SpotinstInstancesMonitor() {
        super("Instances monitor");
        recurrencePeriod = TimeUnit.SECONDS.toMillis(30);
    }
    //endregion

    //region Private Methods
    private void handleGroup(Map<String, Map<String, ContextInstance>> spotRequestWaiting, String groupId) throws IOException {

        Map<String, ContextInstance> spotRequests = spotRequestWaiting.get(groupId);
        Set<String> spotRequestsIds = spotRequests.keySet();
        if (spotRequestsIds.size() > 0) {
            for (String spotRequestId : spotRequestsIds) {
                handleSpotRequest(spotRequests.get(spotRequestId), spotRequestId, groupId);
            }
        } else {
            LOGGER.info("There are no spot requests to handle for group: " + groupId);
        }
    }

    private void handleSpotRequest(ContextInstance contextInstance, String spotRequestId, String groupId) throws IOException {
        boolean isSpotStuck = isTimePassed(contextInstance.getCreatedAt(), TIMEOUT);

        if (isSpotStuck) {
            LOGGER.info("Spot request: " + spotRequestId + " is in waiting state for over than 20 minutes, ignoring this Spot request");
            SpotinstContext.getInstance().removeSpotRequestFromWaiting(groupId, spotRequestId);
        } else {
            SpotRequest spotRequest =
                    SpotinstGateway.getSpotRequest(spotRequestId);

            if (spotRequest != null &&
                    spotRequest.getInstanceId() != null) {

                String instanceId = spotRequest.getInstanceId();
                SpotinstSlave node = (SpotinstSlave) Jenkins.getInstance().getNode(spotRequestId);

                if (node != null) {
                    updateNodeName(contextInstance.getNumOfExecutors(), spotRequestId, instanceId, node);
                }
            }
        }
    }

    private void updateNodeName(Integer numOfExecutors, String spotRequestId, String instanceId, SpotinstSlave node) throws IOException {

        LOGGER.info("Spot request: " + spotRequestId + " is ready, setting the node name to instanceId: " + instanceId);

        Jenkins.getInstance().removeNode(node);
        node.setNodeName(instanceId);
        node.setInstanceId(instanceId);
        Jenkins.getInstance().addNode(node);
        String elastigroupId = node.getElastigroupId();
        String label = null;
        ContextInstance contextInstance = SpotinstContext.getInstance().getSpotRequestWaiting().get(elastigroupId).get(spotRequestId);
        if (contextInstance.getLabel() != null) {
            label = contextInstance.getLabel();
        }
        SpotinstContext.getInstance().addSpotRequestToInitiating(elastigroupId, instanceId, numOfExecutors, label);
        SpotinstContext.getInstance().removeSpotRequestFromWaiting(elastigroupId, spotRequestId);
    }

    private boolean isTimePassed(Date from, Integer minutes) {
        boolean retVal = false;
        Date now = new Date();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(from);
        calendar.add(Calendar.MINUTE, minutes);
        Date timeToPass = calendar.getTime();

        if (now.after(timeToPass)) {
            retVal = true;
        }

        return retVal;
    }

    private void removeStuckInitiatingInstances() {
        Map<String, Map<String, ContextInstance>> spotRequestInitiating =
                SpotinstContext.getInstance().getSpotRequestInitiating();
        if (spotRequestInitiating.size() > 0) {
            Set<String> groupIds = spotRequestInitiating.keySet();
            for (String groupId : groupIds) {
                handleInitiatingForGroup(spotRequestInitiating, groupId);
            }
        }
    }

    private void handleInitiatingForGroup(Map<String, Map<String, ContextInstance>> spotRequestInitiating, String groupId) {
        Map<String, ContextInstance> spotInitiating = spotRequestInitiating.get(groupId);
        Set<String> instanceIds = spotInitiating.keySet();
        for (String instanceId : instanceIds) {
            handleInitiatingInstance(groupId, spotInitiating, instanceId);
        }
    }

    private void handleInitiatingInstance(String groupId, Map<String, ContextInstance> spotInitiating, String instanceId) {
        ContextInstance contextInstance = spotInitiating.get(instanceId);
        boolean isInstanceStuck = isTimePassed(contextInstance.getCreatedAt(), TIMEOUT);
        if (isInstanceStuck) {
            LOGGER.info("Instance: " + instanceId + " is in initiating state for over than 20 minutes, ignoring this instance");
            SpotinstContext.getInstance().removeSpotRequestFromInitiating(groupId, instanceId);
        }
    }
    //endregion

    //region Public Methods
    @Override
    protected void execute(TaskListener taskListener) throws IOException, InterruptedException {

        Map<String, Map<String, ContextInstance>> spotRequestWaiting = SpotinstContext.getInstance().getSpotRequestWaiting();

        if (spotRequestWaiting.size() > 0) {
            Set<String> groupIds = spotRequestWaiting.keySet();

            for (String groupId : groupIds) {
                try {
                    handleGroup(spotRequestWaiting, groupId);
                } catch (Exception e) {
                    LOGGER.info("Waiting list is modified right now, will be handle in next iteration");
                }
            }

        } else {
            LOGGER.info("There are no spot requests to handle");
        }

        removeStuckInitiatingInstances();
    }

    @Override
    public long getRecurrencePeriod() {
        return recurrencePeriod;
    }
    //endregion
}
