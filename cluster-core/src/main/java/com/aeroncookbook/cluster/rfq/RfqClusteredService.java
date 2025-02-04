/*
 * Copyright 2019-2022 Shaun Laurens.
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

package com.aeroncookbook.cluster.rfq;

import com.aeroncookbook.cluster.rfq.demuxer.MasterDemuxer;
import com.aeroncookbook.cluster.rfq.instruments.Instruments;
import com.aeroncookbook.cluster.rfq.statemachine.ClusterProxy;
import com.aeroncookbook.cluster.rfq.statemachine.Rfqs;
import com.aeroncookbook.cluster.rfq.timers.TimerService;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RfqClusteredService implements ClusteredService, ClusterProxy
{
    private final MasterDemuxer demuxer;
    private final Instruments instruments;
    private final Rfqs rfqs;
    private final TimerService timerService;
    private final Logger log = LoggerFactory.getLogger(RfqClusteredService.class);
    private Cluster cluster;
    private ClientSession currentSession;

    public RfqClusteredService()
    {
        this.instruments = new Instruments();
        this.rfqs = new Rfqs(instruments, this, 10_000, 200);
        this.demuxer = new MasterDemuxer(rfqs, instruments);
        this.timerService = new TimerService();
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage)
    {
        this.cluster = cluster;
        if (snapshotImage != null)
        {
            log.info("loading snapshot...");
            do
            {
                int polledItemCount = snapshotImage.poll(demuxer, 1);
                if (polledItemCount == 0)
                {
                    log.info("No more items in snapshot...");
                    break;
                }
            }
            while (true);
        }
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp)
    {
        log.info("Cluster Client Session opened");
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason)
    {
        log.info("Cluster Client Session closed");
        rfqs.cancelOnClusterSessionDisconnect(timestamp, session.id());
    }

    @Override
    public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer, int offset,
                                 int length, Header header)
    {
        demuxer.setSession(session);
        this.currentSession = session;
        demuxer.setClusterTime(timestamp);
        demuxer.onFragment(buffer, offset, length, header);
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp)
    {
        log.info("on timer correlation:{} time:{}", correlationId, timestamp);
        int rfqId = timerService.getRfqIdForCorrelationId(correlationId);
        rfqs.expire(rfqId);
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication)
    {
        log.info("taking snapshot");
        instruments.snapshotTo(snapshotPublication);
        rfqs.snapshotTo(snapshotPublication);
    }

    @Override
    public void onRoleChange(Cluster.Role newRole)
    {
        log.info("Cluster Node is in role {}", newRole.name());
    }

    @Override
    public void onTerminate(Cluster cluster)
    {
        log.info("Cluster Node is terminating");
    }

    @Override
    public void reply(DirectBuffer buffer, int offset, int length)
    {
        currentSession.offer(buffer, offset, length);
    }

    @Override
    public void broadcast(DirectBuffer buffer, int offset, int length)
    {
        cluster.forEachClientSession(cs -> sendToSession(cs, buffer, offset, length));
    }

    private void sendToSession(ClientSession cs, DirectBuffer buffer, int offset, int length)
    {
        cs.offer(buffer, offset, length);
    }

    @Override
    public void scheduleExpiry(long noSoonerThanMs, int rfqId)
    {
        long correlationIdForRfqId = timerService.getCorrelationIdForRfqId(rfqId);
        cluster.scheduleTimer(correlationIdForRfqId, noSoonerThanMs);
    }
}
