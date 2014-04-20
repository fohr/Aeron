/*
 * Copyright 2014 Real Logic Ltd.
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
package uk.co.real_logic.aeron.mediadriver;

import uk.co.real_logic.aeron.util.collections.Long2ObjectHashMap;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.util.protocol.HeaderFlyweight;
import uk.co.real_logic.aeron.util.protocol.StatusMessageFlyweight;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * Frame processing for receivers
 */
public class RcvFrameHandler implements FrameHandler, AutoCloseable
{
    private final UdpTransport transport;
    private final UdpDestination destination;
    private final Long2ObjectHashMap<RcvChannelState> channelInterestMap;
    private final MediaDriverAdminThreadCursor mediaDriverAdminThreadCursor;
    private final ByteBuffer sendBuffer;
    private final AtomicBuffer writeBuffer;
    private final StatusMessageFlyweight statusMessageFlyweight;

    public RcvFrameHandler(final UdpDestination destination,
                           final NioSelector nioSelector,
                           final MediaDriverAdminThreadCursor mediaDriverAdminThreadCursor)
        throws Exception
    {
        this.transport = new UdpTransport(this, destination, nioSelector);
        this.destination = destination;
        this.channelInterestMap = new Long2ObjectHashMap<>();
        this.mediaDriverAdminThreadCursor = mediaDriverAdminThreadCursor;
        this.sendBuffer = ByteBuffer.allocateDirect(StatusMessageFlyweight.LENGTH);
        this.writeBuffer = new AtomicBuffer(sendBuffer);
        this.statusMessageFlyweight = new StatusMessageFlyweight();
    }

    public int sendTo(final ByteBuffer buffer, final long sessionId, final long channelId) throws Exception
    {
        final RcvChannelState channel = channelInterestMap.get(channelId);
        if (null == channel)
        {
            return 0;
        }

        final RcvSessionState session = channel.getSessionState(sessionId);
        if (null == session)
        {
            return 0;
        }

        return sendTo(buffer, session.sourceAddress());
    }

    public int sendTo(final ByteBuffer buffer, final InetSocketAddress addr) throws Exception
    {
        return transport.sendTo(buffer, addr);
    }

    public void close()
    {
        transport.close();
    }

    public UdpDestination destination()
    {
        return destination;
    }

    public Long2ObjectHashMap<RcvChannelState> channelInterestMap()
    {
        return channelInterestMap;
    }

    public void addChannels(final long[] channelIdList)
    {
        for (final long channelId : channelIdList)
        {
            final RcvChannelState channel = channelInterestMap.get(channelId);

            if (null != channel)
            {
                channel.incrementReference();
            }
            else
            {
                channelInterestMap.put(channelId, new RcvChannelState(channelId));
            }
        }
    }

    public void removeChannels(final long[] channelIdList)
    {
        for (final long channelId : channelIdList)
        {
            final RcvChannelState channel = channelInterestMap.get(channelId);

            if (channel == null)
            {
                throw new ReceiverNotRegisteredException("No channel registered on " + channelId);
            }

            if (channel.decrementReference() == 0)
            {
                channelInterestMap.remove(channelId);
            }
        }
    }

    public int channelCount()
    {
        return channelInterestMap.size();
    }

    public void onDataFrame(final DataHeaderFlyweight header, final InetSocketAddress srcAddr)
    {
        final long sessionId = header.sessionId();
        final long channelId = header.channelId();
        final long termId = header.termId();

        final RcvChannelState channelState = channelInterestMap.get(channelId);
        if (null == channelState)
        {
            return;  // not interested in this channel at all
        }

        final RcvSessionState sessionState = channelState.getSessionState(sessionId);
        if (null != sessionState)
        {
            final ByteBuffer termBuffer = sessionState.termBuffer(termId);
            if (null != termBuffer)
            {
                // TODO: process the Data by placing it in the Term Buffer (hot path!)
                // TODO: loss detection not done in this thread. Done in adminThread
                return;
            }
            // if we don't know the term, this will drop down and the term buffer will be created.
        }
        else
        {
            // new session, so make it here and save srcAddr
            channelState.createSessionState(sessionId, srcAddr);
            // TODO: this is a new source, so send 1 SM
        }

        // ask admin thread to create buffer for destination, sessionId, channelId, and termId
        mediaDriverAdminThreadCursor.addCreateRcvTermBufferEvent(destination(), sessionId, channelId, termId);
    }

    public void onControlFrame(final HeaderFlyweight header, final InetSocketAddress srcAddr)
    {
        // this should be on the data channel and shouldn't include NAKs or SMs, so ignore.
    }

    public void attachBufferState(final RcvBufferState buffer)
    {
        final RcvChannelState channelState = channelInterestMap.get(buffer.channelId());
        if (null == channelState)
        {
            throw new IllegalStateException("channel not found");
        }

        final RcvSessionState sessionState = channelState.getSessionState(buffer.sessionId());
        if (null == sessionState)
        {
            throw new IllegalStateException("session not found");
        }

        sessionState.termBuffer(buffer.termId(), buffer.buffer());

        // now we are all setup, so send an SM to allow the source to send if it is waiting
        // TODO: grab initial seqnum from data and store in sessionState somehow (per TermID)
        // TODO: need a strategy object to track the initial receiver window to send in the SMs.
        sendStatusMessage(0, 0, buffer.termId(), sessionState, channelState);
    }

    private int sendStatusMessage(final int seqNum,
                                  final int window,
                                  final long termId,
                                  final RcvSessionState sessionState,
                                  final RcvChannelState channelState)
    {
        statusMessageFlyweight.wrap(writeBuffer, 0);

        statusMessageFlyweight.sessionId(sessionState.sessionId())
                              .channelId(channelState.channelId())
                              .termId(termId)
                              .highestContiguousSequenceNumber(seqNum)
                              .receiverWindow(window)
                              .headerType(HeaderFlyweight.HDR_TYPE_SM)
                              .frameLength(StatusMessageFlyweight.LENGTH)
                              .flags((byte) 0)
                              .version(HeaderFlyweight.CURRENT_VERSION);

        sendBuffer.position(0);
        sendBuffer.limit(StatusMessageFlyweight.LENGTH);

        try
        {
            return transport.sendTo(sendBuffer, sessionState.sourceAddress());
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
