    /**
    -------------------------SendThread主要用于管理客户端和服务器端之间所有网络IO----------------------
    */

        public void run() {
            clientCnxnSocket.introduce(this,sessionId);
            clientCnxnSocket.updateNow();
            clientCnxnSocket.updateLastSendAndHeard();
            int to;
            long lastPingRwServer = System.currentTimeMillis();
            final int MAX_SEND_PING_INTERVAL = 10000; //10 seconds
            while (state.isAlive()) {
                try {
                    // 初始时，sockKey为null，因此会执行下面的if语句，调用startConnect()进行异步连接
                    if (!clientCnxnSocket.isConnected()) {
                        if(!isFirstConnect){
                            try {
                                Thread.sleep(r.nextInt(1000));
                            } catch (InterruptedException e) {
                                LOG.warn("Unexpected exception", e);
                            }
                        }
                        // don't re-establish connection if we are closing
                        if (closing || !state.isAlive()) {
                            break;
                        }
                        /**
                         *客户端开始连接服务端
                         */
                        startConnect();
                        clientCnxnSocket.updateLastSendAndHeard();
                    }

                    if (state.isConnected()) {
                        if (zooKeeperSaslClient != null) {
                            boolean sendAuthEvent = false;
                            if (zooKeeperSaslClient.getSaslState() == ZooKeeperSaslClient.SaslState.INITIAL) {
                                try {
                                    zooKeeperSaslClient.initialize(ClientCnxn.this);
                                } catch (SaslException e) {
                                   LOG.error("SASL authentication with Zookeeper Quorum member failed: " + e);
                                    state = States.AUTH_FAILED;
                                    sendAuthEvent = true;
                                }
                            }
                            KeeperState authState = zooKeeperSaslClient.getKeeperState();
                            if (authState != null) {
                                if (authState == KeeperState.AuthFailed) {
                                    // An authentication error occurred during authentication with the Zookeeper Server.
                                    state = States.AUTH_FAILED;
                                    sendAuthEvent = true;
                                } else {
                                    if (authState == KeeperState.SaslAuthenticated) {
                                        sendAuthEvent = true;
                                    }
                                }
                            }

                            if (sendAuthEvent == true) {
                                eventThread.queueEvent(new WatchedEvent( Watcher.Event.EventType.None, authState,null));
                            }
                        }
                        //下一次select()超时时间
                        to = readTimeout - clientCnxnSocket.getIdleRecv();
                    } else {
                        /**
                        *lastHeard等于while循环之前now的值，在成功连接服务端之前，不会再变
                        *每循环一次，now的值是不断变化的
                        *也就是说，在未连接成功之前idleRecv是不断变大的。
                        */
                        to = connectTimeout - clientCnxnSocket.getIdleRecv();
                    }
                    
                    /**
                     * 当idleRecv>connectTimeout时，也就是连接超时了，就抛出SessionTimeoutException异常。不过抛出异常，线程并不终止，而是进入catch块做处理。
                     */
                    if (to <= 0) {
                        throw new SessionTimeoutException(
                                "Client session timed out, have not heard from server in " + clientCnxnSocket.getIdleRecv() + "ms"
                                        + " for sessionid 0x" + Long.toHexString(sessionId));
                    }
                    
                    /**
                     *如果send空闲，则发送心跳包
                     */
                    if (state.isConnected()) {
                    	//1000(1 second) is to prevent race condition missing to send the second ping
                    	//also make sure not to send too many pings when readTimeout is small 
                        int timeToNextPing = readTimeout / 2 - clientCnxnSocket.getIdleSend() - 
                        		((clientCnxnSocket.getIdleSend() > 1000) ? 1000 : 0);
                        //send a ping request either time is due or no packet sent out within MAX_SEND_PING_INTERVAL
                        if (timeToNextPing <= 0 || clientCnxnSocket.getIdleSend() > MAX_SEND_PING_INTERVAL) {
                            sendPing();
                            clientCnxnSocket.updateLastSend();
                        } else {
                            if (timeToNextPing < to) {
                                to = timeToNextPing;
                            }
                        }
                    }

                    if (state == States.CONNECTEDREADONLY) {
                        long now = System.currentTimeMillis();
                        int idlePingRwServer = (int) (now - lastPingRwServer);
                        if (idlePingRwServer >= pingRwTimeout) {
                            lastPingRwServer = now;
                            idlePingRwServer = 0;
                            pingRwTimeout = Math.min(2*pingRwTimeout, maxPingRwTimeout);
                            pingRwServer();
                        }
                        to = Math.min(to, pingRwTimeout - idlePingRwServer);
                    }
                    
                    /**
                     *调用ClientCnxnSocketNIO类的doTransport()方法，处理I/O
                     */
                    clientCnxnSocket.doTransport(to, pendingQueue, outgoingQueue, ClientCnxn.this);
                } catch (Throwable e) {
                    if (closing) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("An exception was thrown while closing send thread for session 0x"
                                    + Long.toHexString(getSessionId()) + " : " + e.getMessage());
                        }
                        break;
                    } else {
                        if (e instanceof SessionExpiredException) {
                            LOG.info(e.getMessage() + ", closing socket connection");
                        } else if (e instanceof SessionTimeoutException) {
                            LOG.info(e.getMessage() + RETRY_CONN_MSG);
                        } else if (e instanceof EndOfStreamException) {
                            LOG.info(e.getMessage() + RETRY_CONN_MSG);
                        } else if (e instanceof RWServerFoundException) {
                            LOG.info(e.getMessage());
                        } else {
                            LOG.warn( "Session 0x"
                                            + Long.toHexString(getSessionId())
                                            + " for server "
                                            + clientCnxnSocket.getRemoteSocketAddress()
                                            + ", unexpected error"
                                            + RETRY_CONN_MSG, e);
                        }
                        /**
                         *cleanup()做以下几件事
                         *根据sockkey得到SocketChannel，关闭Socket的输入流、输出流、关闭Socket，关闭SocketChannel。
                         *取消sockkey，并将sockkey置为null。

                         *这样就能重新调用startConnect()方法重连接。
                        */
                        cleanup();
                        if (state.isAlive()) {
                            eventThread.queueEvent(new WatchedEvent(
                                    Event.EventType.None,
                                    Event.KeeperState.Disconnected,
                                    null));
                        }
                        //重置时间标志
                        clientCnxnSocket.updateNow();
                        clientCnxnSocket.updateLastSendAndHeard();
                    }
                }
            }
            cleanup();
            //关闭Selector
            clientCnxnSocket.close();
            if (state.isAlive()) {
                eventThread.queueEvent(new WatchedEvent(Event.EventType.None, Event.KeeperState.Disconnected, null));
            }
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(), "SendThread exitedloop.");
        }

       
