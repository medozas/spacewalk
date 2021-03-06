/**
 * Copyright (c) 2009--2015 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.redhat.rhn.taskomatic.task.errata;

import com.redhat.rhn.common.db.datasource.ModeFactory;
import com.redhat.rhn.common.db.datasource.SelectMode;
import com.redhat.rhn.common.db.datasource.WriteMode;
import com.redhat.rhn.common.hibernate.HibernateFactory;
import com.redhat.rhn.domain.action.ActionFactory;
import com.redhat.rhn.domain.action.ActionStatus;
import com.redhat.rhn.domain.action.errata.ErrataAction;
import com.redhat.rhn.domain.channel.Channel;
import com.redhat.rhn.domain.channel.ChannelFactory;
import com.redhat.rhn.domain.errata.Errata;
import com.redhat.rhn.domain.errata.ErrataFactory;
import com.redhat.rhn.domain.org.Org;
import com.redhat.rhn.domain.org.OrgFactory;
import com.redhat.rhn.manager.action.ActionManager;
import com.redhat.rhn.taskomatic.task.TaskConstants;
import com.redhat.rhn.taskomatic.task.threaded.QueueWorker;
import com.redhat.rhn.taskomatic.task.threaded.TaskQueue;

import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Processes an errata for a single org
 * ErrataQueueWorker
 * @version $Rev$
 */
class ErrataQueueWorker implements QueueWorker {

    private Logger logger;
    private Long errataId;
    private Long channelId;
    private Long orgId;
    private TaskQueue parentQueue;

    ErrataQueueWorker(Map<String, Long> row, Logger parentLogger) {
        channelId = row.get("channel_id");
        errataId = row.get("errata_id");
        orgId = row.get("org_id");
        logger = parentLogger;
    }

    public void run() {
        try {
            parentQueue.workerStarting();
            markInProgress();
            ActionStatus queuedStatus = lookupQueuedStatus();
            try {
                Errata errata = loadErrata();
                Channel channel = ChannelFactory.lookupById(channelId);
                if (errata == null || channel == null) {
                    logger.error("Either errata or channel is null, " +
                            "skipping ErrataQueue. (" + errataId + ", " + channelId + ")");
                }
                else {
                    scheduleAutoUpdates(errata, queuedStatus, channel);
                }
            }
            catch (Exception e) {
                logger.error("Errata: " + errataId + ", Org Id: " + orgId, e);
                return;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Scheduling autoupdate actions for errata " +
                        errataId.longValue());
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Processing errata queue for " +
                        errataId.longValue());
            }

            WriteMode marker = ModeFactory.getWriteMode(TaskConstants.MODE_NAME,
                TaskConstants.TASK_QUERY_ERRATA_QUEUE_ENQUEUE_SAT_ERRATA);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("errata_id", errataId);
            params.put("minutes", new Long(0));
            params.put("channel_id", channelId);
            int rowsUpdated = marker.executeUpdate(params);
            if (logger.isDebugEnabled()) {
                logger.debug("inserted " + rowsUpdated +
                    " rows into the rhnErrataNotificationQueue table");
            }
            dequeueErrata();
            HibernateFactory.commitTransaction();
        }
        catch (Exception e) {
            logger.error(e);
            HibernateFactory.rollbackTransaction();
        }
        finally {
            parentQueue.workerDone();
            HibernateFactory.closeSession();
        }
    }

    private void markInProgress() {
        WriteMode m = ModeFactory.getWriteMode(TaskConstants.MODE_NAME,
                TaskConstants.TASK_QUERY_ERRATA_IN_PROGRESS);
        Map<String, Long> params = new HashMap<String, Long>();
        params.put("errata_id", errataId);
        params.put("channel_id", channelId);
        int numRows = m.executeUpdate(params);
        if (logger.isDebugEnabled()) {
            logger.debug("marked " + numRows +
                    " rows as in progress in rhnErrataQueue table");
        }
        HibernateFactory.commitTransaction();
        HibernateFactory.closeSession();
    }

    private void dequeueErrata() {
        WriteMode deqErrata = ModeFactory.getWriteMode(TaskConstants.MODE_NAME,
                TaskConstants.TASK_QUERY_ERRATA_QUEUE_DEQUEUE_ERRATA);
        Map<String, Long> dqeParams = new HashMap<String, Long>();
        dqeParams.put("errata_id", errataId);
        dqeParams.put("channel_id", channelId);
        int eqDeleted = deqErrata.executeUpdate(dqeParams);
        if (logger.isDebugEnabled()) {
            logger.debug("deleted " + eqDeleted +
                    " rows from the rhnErrataQueue table");
        }
    }

    private Errata loadErrata() throws Exception {
        return ErrataFactory.lookupById(new Long(errataId.longValue()));
    }

    private void scheduleAutoUpdates(Errata errata,
                ActionStatus queuedStatus, Channel chan) throws Exception {
        logger.debug("Scheduling auto updates for " + errata.getAdvisoryName() + "(" +
                errata.getId() + ")");
        HibernateFactory.getSession();
        SelectMode select = ModeFactory.getMode(TaskConstants.MODE_NAME,
                TaskConstants.TASK_QUERY_ERRATA_QUEUE_FIND_AUTOUPDATE_SERVERS);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("channel_id", chan.getId());
        params.put("errata_id", errata.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Long>> results = select.execute(params);
        if (results == null || results.size() == 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("No autoupdate servers found for errata " +
                        errata.getId());
            }
            return;
        }
        else if (logger.isDebugEnabled()) {
            logger.debug("Found " + results.size() + " autoupdate servers for " +
                    "errata " + errata.getId());
        }

        for (Map<String, Long> row : results) {
            Long serverId = row.get("server_id");
            Long serverOrgId = row.get("org_id");
            Org org = OrgFactory.lookupById(serverOrgId);
            // Only schedule an Auto Update if the server supports the
            // feature.  We originally calculated this in the driving
            // query but it wasn't performant.
            if (logger.isDebugEnabled()) {
                logger.debug("Scheduling auto update for Errata: " +
 errata.getId() +
                        ", Server: " + serverId + ", Org: " + serverOrgId);
            }
            ErrataAction errataAction = ActionManager.
                createErrataAction(org, errata);
            ActionManager.addServerToAction(serverId, errataAction);
            ActionManager.storeAction(errataAction);
            HibernateFactory.commitTransaction();
            HibernateFactory.closeSession();
        }
        HibernateFactory.commitTransaction();
        HibernateFactory.closeSession();

    }

    private ActionStatus lookupQueuedStatus() {
        ActionStatus queuedStatus = ActionFactory.STATUS_QUEUED;
        if (queuedStatus != null) {
            return queuedStatus;
        }
        logger.error("Couldn't locate \"queued\" action status");
        return null;

    }

    public void setParentQueue(TaskQueue queue) {
        parentQueue = queue;
    }
}
