Session接口：
    public static interface Session {
        long getSessionId();
        int getTimeout();
        boolean isClosing();
    }

Session接口实现类SessionImpl：------此类的对象，就是我们要创建的session
    public static class SessionImpl implements Session {
        SessionImpl(long sessionId, int timeout, long expireTime) {
            this.sessionId = sessionId;
            this.timeout = timeout;
            this.tickTime = expireTime;  //ticktime变量表示这个session下一个超时时间点
            isClosing = false;
        }
    }

/**
关于Session，有三个与超时时间有关的值：
SessionImpl类中有一个属性：tickTime，表示session的超时时间。
    它的值会被touchSession()方法中的expireTime变量更新。

SessionTrackerImpl类中有一个属性：nextExpirationTime，表示下一个超时时间检查点。
    用它做session超时时间判断，一旦当前时间到达nextExpirationTime值，就移除超时时间点是nextExpirationTime的session集合，并将它们置为过期。
    在构造方法中，将它赋值为ticktime的倍数；它的值是不断变化的，每次递增一个ticktime。(总之每次变化，都是ticktime的倍数)

在SessionTrackerImpl类的touchSession()方法中有一个变量expireTime，用它来更新SessionImpl对象的超时时间tickTime
    它的值为值为ticktime的倍数

注意：
SessionTrackerImpl类中用到的ticktime值，是配置文件zoo.cfg中ticktime的值。
*/

Zookeeper服务端的ZookeeperServer类中维护着一个SessionTracker接口对象。
SessionTracker接口的实现类SessionTrackerImpl主要负责维护服务端的session。
SessionTrackerImpl类中有三个集合：
    //保存session实体。key:server端sessionId； value:SessionImpl对象
    HashMap<Long, SessionImpl> sessionsById = new HashMap<Long, SessionImpl>(); 
    //同一个超时时间点的session，给超时线程用. key:tickTime； value:SessionSet，保存SessionImpl对象的Set几个
    HashMap<Long, SessionSet> sessionSets = new HashMap<Long, SessionSet>();    
    //session超时信息。key:server端的sessionId； value:server端的sessionTimeout
    ConcurrentHashMap<Long, Integer> sessionsWithTimeout;                       

    int expirationInterval;   //在构造方法中将它赋值为ticktime，且不会再变
    long nextExpirationTime; 
                              
    // 服务端生成的sessionId
    // 调用initializeNextSession()方法初始化sessionId
    // 之后没创建一个session，sessionId就自增1
    this.nextSessionId = initializeNextSession(sid); 


    


    /**
     *SessionTrackerImpl类也是一个线程
     *线程不断轮询，判断是否有过期的session，如果有，则处理之
     */
    synchronized public void run() {
        try {
            while (running) {
                currentTime = System.currentTimeMillis();
                //下一个超时点没到，就等待
                if (nextExpirationTime > currentTime) {
                    this.wait(nextExpirationTime - currentTime);
                    continue;
                }

                //同一时间点超时的session
                SessionSet set;
                set = sessionSets.remove(nextExpirationTime);
                if (set != null) {
                    for (SessionImpl s : set.sessions) {
                        // 超时session的处理
                        setSessionClosing(s.sessionId);
                        expirer.expire(s);
                    }
                }

                //下一次超时时间检查点
                nextExpirationTime += expirationInterval;
            }
        } catch (InterruptedException e) {
            LOG.error("Unexpected interruption", e);
        }
    }





    /**
     *sessionId初始化算法
     *1字节server_id+当前时间的后5个字节+2字节0，保证全局唯一
     */
    public static long initializeNextSession(long id) {
        long nextSid = 0;
        nextSid = (System.currentTimeMillis() << 24) >>> 8;
        nextSid =  nextSid | (id <<56);
        return nextSid;
    }

    /**
     *创建Session
     */
    synchronized public long createSession(int sessionTimeout) {
        addSession(nextSessionId, sessionTimeout);
        return nextSessionId++;
    }
    synchronized public void addSession(long id, int sessionTimeout) {
        sessionsWithTimeout.put(id, sessionTimeout);
        if (sessionsById.get(id) == null) {
            SessionImpl s = new SessionImpl(id, sessionTimeout, 0);
            sessionsById.put(id, s);
        } else {
        }
        touchSession(id, sessionTimeout);
    }


    /**
     *删除session
     */
    synchronized public void removeSession(long sessionId) {
        SessionImpl s = sessionsById.remove(sessionId);
        sessionsWithTimeout.remove(sessionId);
        if (s != null) {
            SessionSet set = sessionSets.get(s.tickTime);
            if(set != null){
                set.sessions.remove(s);
            }
        }
    }

    /**
     *更新session的超时时间点
     */
    synchronized public boolean touchSession(long sessionId, int timeout) {
        // 根据sessionId得到SessionImpl对象，也就是session
        SessionImpl s = sessionsById.get(sessionId);
        if (s == null || s.isClosing()) {
            return false;
        }
        /**
         *计算得到session的下一个超时点。值为ticktime的倍数
         */
        long expireTime = roundToInterval(System.currentTimeMillis() + timeout);

        //如果新的超时时间点小于当前session的超时时间点，则什么都不做，直接返回
        if (s.tickTime >= expireTime) {
            return true;
        }
        /**
         *下面是需要更新session超时时间点要做的工作
         */
        SessionSet set = sessionSets.get(s.tickTime);
        if (set != null) {
            set.sessions.remove(s);
        }
        s.tickTime = expireTime;
        set = sessionSets.get(s.tickTime);
        if (set == null) {
            set = new SessionSet();
            sessionSets.put(expireTime, set);
        }
        set.sessions.add(s);
        return true;
    }
    private long roundToInterval(long time) {
        return (time / expirationInterval + 1) * expirationInterval;
    }


    /**
     *检查session是否expired或者被删除
     */
    synchronized public void checkSession(long sessionId, Object owner) {
        SessionImpl session = sessionsById.get(sessionId);
        if (session == null || session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }
        if (session.owner == null) {
            session.owner = owner;
        } else if (session.owner != owner) {
            throw new KeeperException.SessionMovedException();
        }
    }
    


    /**-----------------------------server端 Session超时的处理-------------------------------------*/

    /**
     * 1.将session的isClosing变量置为true，相当于标志此session是expired的
     */
    synchronized public void setSessionClosing(long sessionId) {
        if (LOG.isTraceEnabled()) {
            LOG.info("Session closing: 0x" + Long.toHexString(sessionId));
        }
        SessionImpl s = sessionsById.get(sessionId);
        if (s == null) {
            return;
        }
        s.isClosing = true;
    }             

    //ZookeeperServer类
    public void expire(Session session) {
        long sessionId = session.getSessionId();
        LOG.info("Expiring session 0x" + Long.toHexString (sessionId) 
                + ", timeout of " + session.getTimeout() + "ms exceeded" );
        close(sessionId);
    }
    private void close( long sessionId) {
        submitRequest( null, sessionId, OpCode.closeSession, 0, null, null);
    }    
    private void submitRequest(ServerCnxn cnxn, long sessionId, int type,
            int xid, ByteBuffer bb, List<Id> authInfo ) {
        Request si = new Request(cnxn, sessionId, xid, type, bb, authInfo );
        submitRequest(si);
    }

    PrepRequestProcesser类
    case OpCode.closeSession :
       // session下的所有临时节点，需要删除，添加到outstandingChanges队列
       HashSet<String> es = zks.getZKDatabase().getEphemerals(request. sessionId);
       synchronized (zks .outstandingChanges ) {
           for (ChangeRecord c : zks .outstandingChanges ) {
               if (c.stat == null) {
                   // Doing a delete
                   es.remove(c. path);
               } else if (c.stat .getEphemeralOwner() == request.sessionId) {
                   es.add(c. path);
               }
           }
           for (String path2Delete : es) {
               addChangeRecord( new ChangeRecord(request.hdr .getZxid(),
                       path2Delete, null, 0, null ));
           }
           //  设置状态为closing为true
           zks. sessionTracker.setSessionClosing(request.sessionId );
       }

       LOG.info("Processed session termination for sessionid: 0x" + Long. toHexString(request.sessionId));
       break;         

    // DataTree类
    /**
     * 2.session下所有临时节点删除
     */
    void killSession( long session, long zxid) {
        HashSet<String> list = ephemerals.remove(session);
        if (list != null) {
            for (String path : list) {
                try {
                    deleteNode(path, zxid);
                    if (LOG .isDebugEnabled()) {
                        LOG.debug( "Deleting ephemeral node " + path
                                        + " for session 0x"
                                        + Long. toHexString(session));
                    }
                } catch (NoNodeException e) {
                    LOG.warn("Ignoring NoNodeException for path " + path
                            + " while removing ephemeral for dead session 0x"
                            + Long. toHexString(session));
                }
            }
        }
    }           

    /**
     * 3.然后调用SessionTrackerImpl类的removeSession()方法删除超时session
     *   此时server端已经没有超时session的信息了
     */
    synchronized public void removeSession(long sessionId) {
        SessionImpl s = sessionsById.remove(sessionId);
        sessionsWithTimeout.remove(sessionId);
        if (s != null) {
            SessionSet set = sessionSets.get(s.tickTime);
            if(set != null){
                set.sessions.remove(s);
            }
        }
    }

    /**
     * 4.最后FinalRequestProcessor关闭连接
     */
    if (request.hdr != null && request.hdr.getType() == OpCode.closeSession) {  
        ServerCnxnFactory scxn = zks.getServerCnxnFactory();   
        if (scxn != null && request.cnxn == null) {   
            scxn.closeSession(request.sessionId);  
            return;  
        }  
    }
    private void closeSessionWithoutWakeup(long sessionId) {  
        HashSet<NIOServerCnxn> cnxns;  
        synchronized (this.cnxns) {  
            cnxns = (HashSet<NIOServerCnxn>)this.cnxns.clone();  
        }  
  
        for (NIOServerCnxn cnxn : cnxns) {  
            if (cnxn.getSessionId() == sessionId) {  
                try {  
                    // 遍历连接集合，关闭sessionid对应的那个连接
                    cnxn.close();  
                } catch (Exception e) {  
                    LOG.warn("exception during session close", e);  
                }  
            break;  
            }  
        }  
    } 


    /**-------------------------client端对session超时的应对-------------------------------*/
    //server端主动发现session超时并清理session信息，关闭连接，接下来看看client端如何试图恢复session的。
    /**
     * 1.client端处理：
     * 由于server端关闭了socket连接，所以client端会抛出异常，
     * SendThread类会捕获到抛出的异常，并调用cleanup()方法进行处理，然后进行重连
     */
    void doIO(List<Packet> pendingQueue, LinkedList<Packet> outgoingQueue, ClientCnxn cnxn)
      throws InterruptedException, IOException {
        SocketChannel sock = (SocketChannel) sockKey.channel();
        if (sock == null) {
            throw new IOException("Socket is null!");
        }
        if (sockKey.isReadable()) {
            int rc = sock.read(incomingBuffer);
            if (rc < 0) {
                // 抛出异常
                throw new EndOfStreamException(
                        "Unable to read additional data from server sessionid 0x"
                      + Long.toHexString(sessionId) + ", likely server has closed socket");
            }

    /*
      * cleanup()做以下几件事
      * 根据sockkey得到SocketChannel，关闭Socket的输入流、输出流、关闭Socket，关闭SocketChannel。
      * 取消sockkey，并将sockkey置为null。

      * 这样就能重新调用startConnect()方法重连接。
      * 在重连过程中，与新建session时类似，只是发送的sessionId和password是老的。
     */        


    /**
     * 2.server端处理
     */
    public void reopenSession(ServerCnxn cnxn, long sessionId, byte[] passwd,
            int sessionTimeout) throws IOException {
        // 检查密码，如果不一样，则调用finishSessionInit()方法进行处理
        if (!checkPasswd(sessionId, passwd)) {
            finishSessionInit(cnxn, false);
        // 校验session是否还有效，这里不同的server处理不一样
        // 无论是leader还是follower最后都会调用zk.finishSessionInit(cnxn, valid)处理
        // 而由于session已经失效，所以finishSessionInit()方法内部的valid为false
        } else {
            revalidateSession(cnxn, sessionId, sessionTimeout);
        }
    }

    public void finishSessionInit(ServerCnxn cnxn, boolean valid) {
        try {
            /**
             * 由于valid是false，所以返回给client的sessionId为0，sessionTimeout为0，password为空
             */
            ConnectResponse rsp = new ConnectResponse(0, 
                    valid ? cnxn.getSessionTimeout() : 0, 
                    valid ? cnxn.getSessionId() : 0, 
                    valid ? generatePasswd(cnxn.getSessionId()) : new byte[16]);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            bos.writeInt(-1, "len");
            rsp.serialize(bos, "connect");
            if (!cnxn.isOldClient) {
                bos.writeBool( this instanceof ReadOnlyZooKeeperServer, "readOnly");
            }
            baos.close();
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            bb.putInt(bb.remaining() - 4).rewind();
            cnxn.sendBuffer(bb);    

        } catch (Exception e) {
            LOG.warn("Exception while establishing session, closing", e);
            cnxn.close();
        }
    }    


    /**
     * 3.client端处理
     */
    void readConnectResult() throws IOException {  
        ......  
        // client端的sessionId被重置为0了  
        this.sessionId = conRsp.getSessionId();  
        sendThread.onConnected(conRsp.getTimeOut(), this.sessionId, conRsp.getPasswd(), isRO);  
    } 

    void onConnected(int _negotiatedSessionTimeout, long _sessionId,  
                byte[] _sessionPasswd, boolean isRO) throws IOException {  
        negotiatedSessionTimeout = _negotiatedSessionTimeout;  
        // 由于sessionTimeout为0  
        if (negotiatedSessionTimeout <= 0) {  
        /**
         * ①state设为CLOSED，这个行为将关闭SendThread  
         */
        state = States.CLOSED;  
        /**
         * ②Expired状态通知  
         */
        eventThread.queueEvent(new WatchedEvent(  
                        Watcher.Event.EventType.None,  
                        Watcher.Event.KeeperState.Expired, null));  
        /**
         * ③关闭EventThread  
         */
        eventThread.queueEventOfDeath();  
        /**
         * ④抛出SessionExpiredException异常  
         */
        throw new SessionExpiredException(  
                        "Unable to reconnect to ZooKeeper service, session 0x"  
                      + Long.toHexString(sessionId) + " has expired");  
            }  
           ......  
        }     

    /**
     * ⑤SendThread线程捕获到SessionExpiredException异常，
     * 仍然是调用cleanup()方法进行处理；
     * 然后关闭selector
     * 
     * ⑥最后，之后client端将不可用，所有请求发送的时候都将收到SESSIONEXPIRED异常码,
     * 如果需要恢复，则需要重新创建zookeeper实例.
     */
