Session�ӿڣ�
    public static interface Session {
        long getSessionId();
        int getTimeout();
        boolean isClosing();
    }

Session�ӿ�ʵ����SessionImpl��------����Ķ��󣬾�������Ҫ������session
    public static class SessionImpl implements Session {
        SessionImpl(long sessionId, int timeout, long expireTime) {
            this.sessionId = sessionId;
            this.timeout = timeout;
            this.tickTime = expireTime;  //ticktime������ʾ���session��һ����ʱʱ���
            isClosing = false;
        }
    }

/**
����Session���������볬ʱʱ���йص�ֵ��
SessionImpl������һ�����ԣ�tickTime����ʾsession�ĳ�ʱʱ�䡣
    ����ֵ�ᱻtouchSession()�����е�expireTime�������¡�

SessionTrackerImpl������һ�����ԣ�nextExpirationTime����ʾ��һ����ʱʱ����㡣
    ������session��ʱʱ���жϣ�һ����ǰʱ�䵽��nextExpirationTimeֵ�����Ƴ���ʱʱ�����nextExpirationTime��session���ϣ�����������Ϊ���ڡ�
    �ڹ��췽���У�������ֵΪticktime�ı���������ֵ�ǲ��ϱ仯�ģ�ÿ�ε���һ��ticktime��(��֮ÿ�α仯������ticktime�ı���)

��SessionTrackerImpl���touchSession()��������һ������expireTime������������SessionImpl����ĳ�ʱʱ��tickTime
    ����ֵΪֵΪticktime�ı���

ע�⣺
SessionTrackerImpl�����õ���ticktimeֵ���������ļ�zoo.cfg��ticktime��ֵ��
*/

Zookeeper����˵�ZookeeperServer����ά����һ��SessionTracker�ӿڶ���
SessionTracker�ӿڵ�ʵ����SessionTrackerImpl��Ҫ����ά������˵�session��
SessionTrackerImpl�������������ϣ�
    //����sessionʵ�塣key:server��sessionId�� value:SessionImpl����
    HashMap<Long, SessionImpl> sessionsById = new HashMap<Long, SessionImpl>(); 
    //ͬһ����ʱʱ����session������ʱ�߳���. key:tickTime�� value:SessionSet������SessionImpl�����Set����
    HashMap<Long, SessionSet> sessionSets = new HashMap<Long, SessionSet>();    
    //session��ʱ��Ϣ��key:server�˵�sessionId�� value:server�˵�sessionTimeout
    ConcurrentHashMap<Long, Integer> sessionsWithTimeout;                       

    int expirationInterval;   //�ڹ��췽���н�����ֵΪticktime���Ҳ����ٱ�
    long nextExpirationTime; 
                              
    // ��������ɵ�sessionId
    // ����initializeNextSession()������ʼ��sessionId
    // ֮��û����һ��session��sessionId������1
    this.nextSessionId = initializeNextSession(sid); 


    


    /**
     *SessionTrackerImpl��Ҳ��һ���߳�
     *�̲߳�����ѯ���ж��Ƿ��й��ڵ�session������У�����֮
     */
    synchronized public void run() {
        try {
            while (running) {
                currentTime = System.currentTimeMillis();
                //��һ����ʱ��û�����͵ȴ�
                if (nextExpirationTime > currentTime) {
                    this.wait(nextExpirationTime - currentTime);
                    continue;
                }

                //ͬһʱ��㳬ʱ��session
                SessionSet set;
                set = sessionSets.remove(nextExpirationTime);
                if (set != null) {
                    for (SessionImpl s : set.sessions) {
                        // ��ʱsession�Ĵ���
                        setSessionClosing(s.sessionId);
                        expirer.expire(s);
                    }
                }

                //��һ�γ�ʱʱ�����
                nextExpirationTime += expirationInterval;
            }
        } catch (InterruptedException e) {
            LOG.error("Unexpected interruption", e);
        }
    }





    /**
     *sessionId��ʼ���㷨
     *1�ֽ�server_id+��ǰʱ��ĺ�5���ֽ�+2�ֽ�0����֤ȫ��Ψһ
     */
    public static long initializeNextSession(long id) {
        long nextSid = 0;
        nextSid = (System.currentTimeMillis() << 24) >>> 8;
        nextSid =  nextSid | (id <<56);
        return nextSid;
    }

    /**
     *����Session
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
     *ɾ��session
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
     *����session�ĳ�ʱʱ���
     */
    synchronized public boolean touchSession(long sessionId, int timeout) {
        // ����sessionId�õ�SessionImpl����Ҳ����session
        SessionImpl s = sessionsById.get(sessionId);
        if (s == null || s.isClosing()) {
            return false;
        }
        /**
         *����õ�session����һ����ʱ�㡣ֵΪticktime�ı���
         */
        long expireTime = roundToInterval(System.currentTimeMillis() + timeout);

        //����µĳ�ʱʱ���С�ڵ�ǰsession�ĳ�ʱʱ��㣬��ʲô��������ֱ�ӷ���
        if (s.tickTime >= expireTime) {
            return true;
        }
        /**
         *��������Ҫ����session��ʱʱ���Ҫ���Ĺ���
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
     *���session�Ƿ�expired���߱�ɾ��
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
    


    /**-----------------------------server�� Session��ʱ�Ĵ���-------------------------------------*/

    /**
     * 1.��session��isClosing������Ϊtrue���൱�ڱ�־��session��expired��
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

    //ZookeeperServer��
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

    PrepRequestProcesser��
    case OpCode.closeSession :
       // session�µ�������ʱ�ڵ㣬��Ҫɾ������ӵ�outstandingChanges����
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
           //  ����״̬ΪclosingΪtrue
           zks. sessionTracker.setSessionClosing(request.sessionId );
       }

       LOG.info("Processed session termination for sessionid: 0x" + Long. toHexString(request.sessionId));
       break;         

    // DataTree��
    /**
     * 2.session��������ʱ�ڵ�ɾ��
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
     * 3.Ȼ�����SessionTrackerImpl���removeSession()����ɾ����ʱsession
     *   ��ʱserver���Ѿ�û�г�ʱsession����Ϣ��
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
     * 4.���FinalRequestProcessor�ر�����
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
                    // �������Ӽ��ϣ��ر�sessionid��Ӧ���Ǹ�����
                    cnxn.close();  
                } catch (Exception e) {  
                    LOG.warn("exception during session close", e);  
                }  
            break;  
            }  
        }  
    } 


    /**-------------------------client�˶�session��ʱ��Ӧ��-------------------------------*/
    //server����������session��ʱ������session��Ϣ���ر����ӣ�����������client�������ͼ�ָ�session�ġ�
    /**
     * 1.client�˴���
     * ����server�˹ر���socket���ӣ�����client�˻��׳��쳣��
     * SendThread��Ჶ���׳����쳣��������cleanup()�������д���Ȼ���������
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
                // �׳��쳣
                throw new EndOfStreamException(
                        "Unable to read additional data from server sessionid 0x"
                      + Long.toHexString(sessionId) + ", likely server has closed socket");
            }

    /*
      * cleanup()�����¼�����
      * ����sockkey�õ�SocketChannel���ر�Socket������������������ر�Socket���ر�SocketChannel��
      * ȡ��sockkey������sockkey��Ϊnull��

      * �����������µ���startConnect()���������ӡ�
      * �����������У����½�sessionʱ���ƣ�ֻ�Ƿ��͵�sessionId��password���ϵġ�
     */        


    /**
     * 2.server�˴���
     */
    public void reopenSession(ServerCnxn cnxn, long sessionId, byte[] passwd,
            int sessionTimeout) throws IOException {
        // ������룬�����һ���������finishSessionInit()�������д���
        if (!checkPasswd(sessionId, passwd)) {
            finishSessionInit(cnxn, false);
        // У��session�Ƿ���Ч�����ﲻͬ��server����һ��
        // ������leader����follower��󶼻����zk.finishSessionInit(cnxn, valid)����
        // ������session�Ѿ�ʧЧ������finishSessionInit()�����ڲ���validΪfalse
        } else {
            revalidateSession(cnxn, sessionId, sessionTimeout);
        }
    }

    public void finishSessionInit(ServerCnxn cnxn, boolean valid) {
        try {
            /**
             * ����valid��false�����Է��ظ�client��sessionIdΪ0��sessionTimeoutΪ0��passwordΪ��
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
     * 3.client�˴���
     */
    void readConnectResult() throws IOException {  
        ......  
        // client�˵�sessionId������Ϊ0��  
        this.sessionId = conRsp.getSessionId();  
        sendThread.onConnected(conRsp.getTimeOut(), this.sessionId, conRsp.getPasswd(), isRO);  
    } 

    void onConnected(int _negotiatedSessionTimeout, long _sessionId,  
                byte[] _sessionPasswd, boolean isRO) throws IOException {  
        negotiatedSessionTimeout = _negotiatedSessionTimeout;  
        // ����sessionTimeoutΪ0  
        if (negotiatedSessionTimeout <= 0) {  
        /**
         * ��state��ΪCLOSED�������Ϊ���ر�SendThread  
         */
        state = States.CLOSED;  
        /**
         * ��Expired״̬֪ͨ  
         */
        eventThread.queueEvent(new WatchedEvent(  
                        Watcher.Event.EventType.None,  
                        Watcher.Event.KeeperState.Expired, null));  
        /**
         * �۹ر�EventThread  
         */
        eventThread.queueEventOfDeath();  
        /**
         * ���׳�SessionExpiredException�쳣  
         */
        throw new SessionExpiredException(  
                        "Unable to reconnect to ZooKeeper service, session 0x"  
                      + Long.toHexString(sessionId) + " has expired");  
            }  
           ......  
        }     

    /**
     * ��SendThread�̲߳���SessionExpiredException�쳣��
     * ��Ȼ�ǵ���cleanup()�������д���
     * Ȼ��ر�selector
     * 
     * �����֮��client�˽������ã����������͵�ʱ�򶼽��յ�SESSIONEXPIRED�쳣��,
     * �����Ҫ�ָ�������Ҫ���´���zookeeperʵ��.
     */
