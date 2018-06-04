/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Aion foundation.
 */
package org.aion.p2p.impl1.tasks;

import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aion.p2p.INode;
import org.aion.p2p.INodeMgr;
import org.aion.p2p.IP2pMgr;
import org.aion.p2p.P2pConstant;

public class TaskSend implements Runnable {

    private static final int TOTAL_LANE = Math
        .min(Runtime.getRuntime().availableProcessors() << 1, 32);
    private static final int threadQlimit = 20000;

    private final IP2pMgr mgr;
    private final AtomicBoolean start;
    private final BlockingQueue<MsgOut> sendMsgQue;
    private final INodeMgr nodeMgr;
    private final Selector selector;
    private final int lane;

    private static ThreadPoolExecutor tpe;

    public TaskSend(
        final IP2pMgr _mgr,
        final int _lane,
        final BlockingQueue<MsgOut> _sendMsgQue,
        final AtomicBoolean _start,
        final INodeMgr _nodeMgr,
        final Selector _selector) {

        this.mgr = _mgr;
        this.lane = _lane;
        this.sendMsgQue = _sendMsgQue;
        this.start = _start;
        this.nodeMgr = _nodeMgr;
        this.selector = _selector;

        if (tpe == null) {
            tpe = new ThreadPoolExecutor(TOTAL_LANE
                , TOTAL_LANE
                , 0
                , TimeUnit.MILLISECONDS
                , new LinkedBlockingQueue<>(threadQlimit)
                , Executors.defaultThreadFactory());
        }
    }

    @Override
    public void run() {
        while (start.get()) {
            try {
                MsgOut mo = sendMsgQue.take();

                // if timeout , throw away this msg.
                long now = System.currentTimeMillis();
                if (now - mo.getTimestamp() > P2pConstant.WRITE_MSG_TIMEOUT) {
                    if (this.mgr.isShowLog()) {
                        System.out.println(getTimeoutMsg(mo.getDisplayId(), now));
                    }
                    continue;
                }

                // if not belong to current lane, put it back.
                long t1 = System.nanoTime();
                if (mo.getLane() != lane) {
                    sendMsgQue.offer(mo);
                    continue;
                }

                INode node = null;
                switch (mo.getDest()) {
                    case ACTIVE:
                        node = nodeMgr.getActiveNode(mo.getNodeId());
                        break;
                    case INBOUND:
                        node = nodeMgr.getInboundNode(mo.getNodeId());
                        break;
                    case OUTBOUND:
                        node = nodeMgr.getOutboundNode(mo.getNodeId());
                        break;
                }

                if (node != null) {
                    SelectionKey sk = node.getChannel().keyFor(selector);
                    if (sk != null) {
                        Object attachment = sk.attachment();
                        if (attachment != null) {
                            tpe.execute(new TaskWrite(
                                this.mgr.isShowLog(),
                                node.getIdShort(),
                                node.getChannel(),
                                mo.getMsg(),
                                (ChannelBuffer) attachment,
                                this.mgr));
                        }
                    }
                } else {
                    if (this.mgr.isShowLog()) {
                        System.out
                            .println(getNodeNotExitMsg(mo.getDest().name(), mo.getDisplayId()));
                    }
                }
            } catch (InterruptedException e) {
                if (this.mgr.isShowLog()) {
                    System.out.println("<p2p task-send-interrupted>");
                }
                return;
            } catch (RejectedExecutionException e) {
                if (this.mgr.isShowLog()) {
                    System.out.println("<p2p task-send-reached thread queue limit>");
                }
            } catch (Exception e) {
                if (this.mgr.isShowLog()) {
                    e.printStackTrace();
                }
            }
        }
    }

    // hash mapping channel id to write thread.
    static int hash2Lane(int in) {
        in ^= in >> (32 - 5);
        in ^= in >> (32 - 10);
        in ^= in >> (32 - 15);
        in ^= in >> (32 - 20);
        in ^= in >> (32 - 25);
        return (in & 0b11111) * TOTAL_LANE / 32;
    }

    private String getTimeoutMsg(String id, long now) {
        return "<p2p timeout-msg to-node=" + id + " timestamp=" + now + ">";
    }

    private String getNodeNotExitMsg(String name, String id) {
        return "<p2p msg-" + name + "->" + id + " node-not-exit";
    }
}
