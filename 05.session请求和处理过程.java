
    //------------------------------------------客户端开始连接服务端、并创建session------------------------------------------------
    /**
      *使用NIO+SocketChannel的方式，从serverAddrs中挑选一个地址去“异步”连接
      */
    /**
     * SendThread类的run()方法中
     */
    private void startConnect() throws IOException {
        //改变zookeeper状态
        state = States.CONNECTING;
        //从serverAddress中取出一个地址
        InetSocketAddress addr;
        if (rwServerAddress != null) {
            addr = rwServerAddress;
            rwServerAddress = null;
        } else {
            addr = hostProvider.next(1000);
        }
        setName(getName().replaceAll("\\(.*\\)", "(" + addr.getHostName() + ":" + addr.getPort() + ")"));
        if (ZooKeeperSaslClient.isEnabled()) {
            try {
                String principalUserName = System.getProperty( ZK_SASL_CLIENT_USERNAME, "zookeeper");
                zooKeeperSaslClient = new ZooKeeperSaslClient( principalUserName+"/"+addr.getHostName());
            } catch (LoginException e) {
                LOG.warn("SASL configuration failed: " + e + " Will continue connection to Zookeeper server without "
                  + "SASL authentication, if Zookeeper server allows it.");
                eventThread.queueEvent(new WatchedEvent(
                  Watcher.Event.EventType.None,
                  Watcher.Event.KeeperState.AuthFailed, null));
                saslLoginFailed = true;
            }
        }
        logStartConnect(addr);
        //开始异步连接
        clientCnxnSocket.connect(addr);
    }
    
    void connect(InetSocketAddress addr) throws IOException {
        SocketChannel sock = createSock();
        try {
           registerAndConnect(sock, addr);
        } catch (IOException e) {
            LOG.error("Unable to open socket to " + addr);
            sock.close();
            throw e;
        }
        /**
         *标识Session还未创建
         */
        initialized = false;
        //重置2个读buffer，准备下一次读
        lenBuffer.clear();
        incomingBuffer = lenBuffer;
    }

    SocketChannel createSock() throws IOException {
        SocketChannel sock;
        sock = SocketChannel.open();
        sock.configureBlocking(false);
        sock.socket().setSoLinger(false, -1);
        sock.socket().setTcpNoDelay(true);
        return sock;
    }

    void registerAndConnect(SocketChannel sock, InetSocketAddress addr) throws IOException {
        sockKey = sock.register(selector, SelectionKey.OP_CONNECT);
        boolean immediateConnect = sock.connect(addr);
        if (immediateConnect) {
            sendThread.primeConnection();
        }
    }


    /**
     *执行完startConnect()方法后，继续在run()方法中向下执行，执行到doTransport()方法
     *ClientCnxnSocketNIO类
     */
    void doTransport(int waitTimeOut, List<Packet> pendingQueue, LinkedList<Packet> outgoingQueue, ClientCnxn cnxn) throws IOException, InterruptedException {
        // 调用Selector的select()方法开始轮询注册的通道，是否有准备好某操作的通道
        selector.select(waitTimeOut);
        Set<SelectionKey> selected;
        synchronized (this) {
            selected = selector.selectedKeys();
        }
        updateNow();
        for (SelectionKey k : selected) {
            SocketChannel sc = ((SocketChannel) k.channel());

            if ((k.readyOps() & SelectionKey.OP_CONNECT) != 0) {
                /**
                 *如果没有连接成功，则继续在SendThread的run()方法中循环，直到连接成功，或者连接时长超过客户端的连接超时时间connectTimeout并抛出异常
                 */
                if (sc.finishConnect()) {
                    updateLastSendAndHeard();
                    /**
                     *到这里，说明socket connection已经创建完成，但zookeeper的状态还没有变为CONNECTED。接下来就要开始调用primeConnection()初始化Session了。
                     *同时改变key对应的通道感兴趣的操作为读和写
                     */
                    sendThread.primeConnection();
                }
            } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                doIO(pendingQueue, outgoingQueue, cnxn);
            }
        }
        if (sendThread.getZkState().isConnected()) {
            synchronized(outgoingQueue) {
                if (findSendablePacket(outgoingQueue, cnxn.sendThread.clientTunneledAuthenticationInProgress()) != null) {
                    enableWrite();
                }
            }
        }
        selected.clear();
    }
    
    /**
     *SendThread类
     */
    void primeConnection() throws IOException {
        LOG.info("Socket connection established to " + clientCnxnSocket.getRemoteSocketAddress() + ", initiating session");
        isFirstConnect = false;
        /**
         *在第一次连接服务端，创建connection，创建Session时，seenRwServerBefore，所以客户端第一次连接服务端时，传递的sessionId为0。

         *一旦成功连接服务端，并创建session，seenRwServerBefore将会被置为true，
         *因此客户端发生ConnectionLoss，进行重连时，发送的sessionId就是从服务端接受来的sessionId
         */
        long sessId = (seenRwServerBefore) ? sessionId : 0;
        /**
         *创建连接请求
         *第一次连接服务端时，sessionId为0，sessionPasswd为长度16的空字节数组, 
                              sessionTimeout为客户端Zookeeper构造方法是传递的sessiontimeout值
         *一旦连接成功过，再次重连时，发送的sessionId和sessionPasswd就是之前从服务端接收的sessionId和sessionPasswd
         */
        ConnectRequest conReq = new ConnectRequest(0, lastZxid, sessionTimeout, sessId, sessionPasswd);

        synchronized (outgoingQueue) {
            // disableAutoWatchReset:false
            if (!disableAutoWatchReset) {
                List<String> dataWatches = zooKeeper.getDataWatches();
                List<String> existWatches = zooKeeper.getExistWatches();
                List<String> childWatches = zooKeeper.getChildWatches();
                if (!dataWatches.isEmpty() || !existWatches.isEmpty() || !childWatches.isEmpty()) {
                    SetWatches sw = new SetWatches(lastZxid, prependChroot(dataWatches), prependChroot(existWatches), prependChroot(childWatches));
                    RequestHeader h = new RequestHeader();
                    h.setType(ZooDefs.OpCode.setWatches);
                    h.setXid(-8);
                    Packet packet = new Packet(h, new ReplyHeader(), sw, null, null);
                    outgoingQueue.addFirst(packet);
                }
            }

            for (AuthData id : authInfo) {
                outgoingQueue.addFirst(new Packet(new RequestHeader(-4, OpCode.auth), null, new AuthPacket(0, id.scheme, id.data), null, null));
            }
            
            /**
             * 用ConnectRequest当作参数创建Packet对象:ConnectRequest===>Packet，
             * 传给outgoingQueue队列中，供ClientCnxnSocketNIO类的doIO()方法处理
             */
            outgoingQueue.addFirst(new Packet(null, null, conReq, null, null, readOnly));
        }
        /**
         *将key对应的通道感兴趣的操作改为读和写
         */
        clientCnxnSocket.enableReadWriteOnly();
    }


    /**
     *ClientCnxnSocketNIO类
     */
    void doIO(List<Packet> pendingQueue, LinkedList<Packet> outgoingQueue, ClientCnxn cnxn)
      throws InterruptedException, IOException {
        SocketChannel sock = (SocketChannel) sockKey.channel();
        if (sock == null) {
            throw new IOException("Socket is null!");
        }
        /**
         *向服务端发送数据
         */
        if (sockKey.isWritable()) {
            synchronized(outgoingQueue) {
                // 从发送队列中拿请求
                Packet p = findSendablePacket(outgoingQueue,cnxn.sendThread.clientTunneledAuthenticationInProgress());

                if (p != null) {
                    updateLastSend();
                    if (p.bb == null) {
                        if ((p.requestHeader != null) && (p.requestHeader.getType() != OpCode.ping) && (p.requestHeader.getType() != OpCode.auth)) {
                            //如果是业务请求，则需要设置事务Id. xid表示事务id，自增，放在请求头中
                            p.requestHeader.setXid(cnxn.getXid());
                        }
                        /**
                         *创建ByteBuffer，并将请求内容序列化到ByteBuffer中去
                         */
                        p.createBB();  
                    }
                    /**
                     *向SocketChannel中写数据
                     */
                    sock.write(p.bb);  

                    if (!p.bb.hasRemaining()) { //写完了，数据发送成功
                        sentCount++;            //已发送的业务Packet数量
                        outgoingQueue.removeFirstOccurrence(p); //发送完了，那从发送队列删掉，方便后续发送请求处理
                        /**
                         *如果是业务请求，则添加到Pending队列，方便在server端返回后客户端做相应处理，如果是其他请求，发完就扔了
                         */
                        if (p.requestHeader != null && p.requestHeader.getType() != OpCode.ping && p.requestHeader.getType() != OpCode.auth) {
                            synchronized (pendingQueue) {
                                pendingQueue.add(p);
                            }
                        }
                    }
                }
                //请求发完了，不需要再监听OS的写事件了，如果没发完，那还是要继续监听的，继续写嘛
                if (outgoingQueue.isEmpty()) {
                    disableWrite();
                } else if (!initialized && p != null && !p.bb.hasRemaining()) {
                    disableWrite();
                } else {
                    enableWrite();
                }
            }
        }
    }

    /**
     *Packet类的createBB()方法
     */
    public void createBB() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            //写一个int，站位用，整个packet写完会来更新这个值，代表packet的从长度，4个字节
            boa.writeInt(-1, "len"); 
            //序列化请求头
            if (requestHeader != null) {
                requestHeader.serialize(boa, "header");
            }
            //序列化请求体
            if (request instanceof ConnectRequest) {
                request.serialize(boa, "connect");
                boa.writeBool(readOnly, "readOnly");
            } else if (request != null) {
                request.serialize(boa, "request");
            }
            baos.close();
            this.bb = ByteBuffer.wrap(baos.toByteArray());  //生成ByteBuffer
            this.bb.putInt(this.bb.capacity() - 4);  //将bytebuffer的前4个字节修改成真正的长度，总长度减去一个int的长度头
            this.bb.rewind();  //准备给后续读
        } catch (IOException e) {
            LOG.warn("Ignoring unexpected exception", e);
        }
    }

    
    //-------------------------------------------------------服务端处理---------------------------------------------
    /**
     *前面在ClientCnxnSocketNIO类的doIO()方法中，向SocketChannel中写入了数据，也就是客户端向服务端发送了数据
     *接下来看看服务端是怎么处理ConnectRequest请求的。
     */

    /**
      在NIOServerCnxn类的doIO()方法中：
     */
     if (k.isReadable()) {
         //先从Channel读4个字节，代表头
         int rc = sock.read(incomingBuffer);
         if (rc < 0) {
             throw new EndOfStreamException(
                     "Unable to read additional data from client sessionid 0x"
                     + Long.toHexString(sessionId)
                     + ", likely client has closed socket");
         }
         if (incomingBuffer.remaining() == 0) {
             boolean isPayload;
             if (incomingBuffer == lenBuffer) { 
                 incomingBuffer.flip();
                 //刚开始读取的4个字节只是负载数据的长度，没有什么实际价值
                 //给incomingBuffer分配一个length长度的内存，将后续的数据都给读进来
                 isPayload = readLength(k);
                 incomingBuffer.clear(); //将incomingBuffer转化为写模式
             } else {
                 isPayload = true;
             }
             if (isPayload) {
                 readPayload();   //读取后续数据
             }
             else {
                 return;
             }
         }
     }
    
    /**
     *NIOServerCnxn类
     */
    private void readPayload() throws IOException, InterruptedException {
        if (incomingBuffer.remaining() != 0) { 
            //尝试把数据一次性读进来
            int rc = sock.read(incomingBuffer); 
            if (rc < 0) {
                throw new EndOfStreamException(
                        "Unable to read additional data from client sessionid 0x"
                        + Long.toHexString(sessionId)
                        + ", likely client has closed socket");
            }
        }
        //ok，一次读完
        if (incomingBuffer.remaining() == 0) { 
            packetReceived(); //server的packet统计
            incomingBuffer.flip();
            if (!initialized) { //因为Session还未创建，所以initialized为false
                readConnectRequest();
            } else {
                readRequest();
            }
            //清理现场，为下一个packet读做准备
            lenBuffer.clear();
            incomingBuffer = lenBuffer;
        }
    }

    /**
     *NIOServerCnxn类
     */
    private void readConnectRequest() throws IOException, InterruptedException {
        if (zkServer == null) {
            throw new IOException("ZooKeeperServer not running");
        }
        /**
         *开始执行ConnectRequest的处理链
         */
        zkServer.processConnectRequest(this, incomingBuffer);
        /**
         *将initialized置为true
         */
        initialized = true;
    }


    /**
     * ZookeeperServer类
     * cnxn是ZookeeperServer类的对象，也就是服务端实例
     */
    public void processConnectRequest(ServerCnxn cnxn, ByteBuffer incomingBuffer) throws IOException {
        BinaryInputArchive bia = BinaryInputArchive.getArchive(new ByteBufferInputStream(incomingBuffer));
        ConnectRequest connReq = new ConnectRequest();
        /**
         *反序列化得到ConnectRequest对象
         */
        connReq.deserialize(bia, "connect");

        boolean readOnly = false;
        try {
            readOnly = bia.readBool("readOnly");
            cnxn.isOldClient = false;
        } catch (IOException e) {
            LOG.warn("Connection request from old client " + cnxn.getRemoteSocketAddress() + "; will be dropped if server is in r-o mode");
        }
        //.........

        /*开始设置服务端Session相关参数*/
        int sessionTimeout = connReq.getTimeOut();  //拿到客户端sessionTimeout
        byte passwd[] = connReq.getPasswd();        //拿到客户端sessionPasswd
        int minSessionTimeout = getMinSessionTimeout();//服务端minSessionTimeout (默认值为：tickTime * 2)
        if (sessionTimeout < minSessionTimeout) {
            sessionTimeout = minSessionTimeout;
        }
        int maxSessionTimeout = getMaxSessionTimeout();//服务端maxSessionTimeout (默认值为 : tickTime * 20) 
        if (sessionTimeout > maxSessionTimeout) {
            sessionTimeout = maxSessionTimeout;
        }
        /**
         *设置服务端sessionTimeout。
         *只要客户端设置的sessionTimeout在minSessionTimeout和maxSessionTimeout之间，那么这也是服务端的sessionTimeout。
         */
        cnxn.setSessionTimeout(sessionTimeout);

        //暂时先不读后续请求了，直到session建立
        cnxn.disableRecv();
        //拿到客户端sessionId，客户端进行连接操作时，传递给服务端的sessionId为0
        long sessionId = connReq.getSessionId();
        if (sessionId != 0) {
            long clientSessionId = connReq.getSessionId();
            LOG.info("Client attempting to renew session 0x" + Long.toHexString(clientSessionId) + " at " + cnxn.getRemoteSocketAddress());
            serverCnxnFactory.closeSession(sessionId);
            cnxn.setSessionId(sessionId);
            reopenSession(cnxn, sessionId, passwd, sessionTimeout);
        } else {
            LOG.info("Client attempting to establish new session at " + cnxn.getRemoteSocketAddress());
            /**
             *调用createSession()方法创建新Session
             */
            createSession(cnxn, passwd, sessionTimeout);
        }
    }

    /**
     *ZookeeperServer类
     *passwd为客户端传递过来的长度16的空字节数组
     *timeout为服务端的sessionTimeout
     */
    long createSession(ServerCnxn cnxn, byte passwd[], int timeout) {
        /**
         *server端创建session，sessionId是从一个初始化sessionId自增
         */
        long sessionId = sessionTracker.createSession(timeout);
        /**
         * 生成passwd（随机的）
         */
        Random r = new Random(sessionId ^ superSecret);
        r.nextBytes(passwd);


        ByteBuffer to = ByteBuffer.allocate(4);
        to.putInt(timeout);
        /**
         *设置server段sessionId。每个server端连接都有一个唯一的SessionId 
         */
        cnxn.setSessionId(sessionId);

        //提交请求给后面的执行链
        submitRequest(cnxn, sessionId, OpCode.createSession, 0, to, null);
        return sessionId;
    }

    /**
     *ZookeeperServer类
     */
    private void submitRequest(ServerCnxn cnxn, long sessionId, int type,
            int xid, ByteBuffer bb, List<Id> authInfo) {
        /**
         *cnxn:NIOServerCnxn对象
         *sessionId:服务端sessionId
         *xid：0 (赋给Request类的cxid属性)
         *type:OpCode.createSession
         *bb：前4个字节为服务端的sessionTimeout （赋给Request类的request属性）
         *authInfo：null

         *由这几个参数创建Request对象

         *接下来就要进入server端的RequestProcessors链，而在执行链之间传递的参数就是Request对象。Request对象包含多个信息。
         */
        Request si = new Request(cnxn, sessionId, xid, type, bb, authInfo);
        submitRequest(si);
    }

    /**
     *ZookeeperServer类
     */
    public void submitRequest(Request si) {
        ......
        try {
            /**
             *
             */
            touch(si.cnxn);
            boolean validpacket = Request.isValid(si.type);
            if (validpacket) {
                /**
                 *将Request对象交给执行链的第一个请求处理器PrepRequestProcessor处理
                 *PrepRequestProcessor类也是一个线程类，其run()方法不断while循环，从submittedRequests队列中取出request对象,然后调用它的pRequest()方法进行处理
                 */
                firstProcessor.processRequest(si);
                if (si.cnxn != null) {
                    incInProcess();
                }
            } 
        ......
    }

    /**
     *PrepRequestProcessor类

     *由pRequest2Txn()方法中做的事情可知，PrepRequestProcessor主要是负责组装Request的事务头hdr参数、事务体txn参数
     */
    protected void pRequest(Request request) throws RequestProcessorException {
        ......
        case OpCode.createSession:
        case OpCode.closeSession:
            // 参数zks.getNextZxid()，获取ZookeeperServer类的hzxid变量值，其从0自增。
            pRequest2Txn(request.type, zks.getNextZxid(), request, null, true);
            break;
        ......
        //获取ZookeeperServer类的hzxid变量值，并赋给Request对象的zxid变量。
        request.zxid = zks.getZxid();

        // 将request交给执行链的下一个请求处理器SyncRequestProcessor去处理。
        // SyncRequestProcessor类同样也是一个线程类，其run()方法不断while循环，从queuedRequests队列中取出request对象处理
        nextProcessor.processRequest(request);
    }

    /**
     *PrepRequestProcessor类
     */
    protected void pRequest2Txn(int type, long zxid, Request request, Record record, boolean deserialize)
        throws KeeperException, IOException, RequestProcessorException {
        /**
         * 为Request对象设置事务头hdr属性
         * cxid为0；zxid是自增的
         */
        request.hdr = new TxnHeader(request.sessionId, request.cxid, zxid, zks.getTime(), type);
        ......
        case OpCode.createSession:
            request.request.rewind();
            int to = request.request.getInt();
            /**
             *创建CreateSessionTxn对象，其只有一个属性:timeOut，将服务端的sessionTimeout赋值给它，
             */
            request.txn = new CreateSessionTxn(to);
            request.request.rewind();

            zks.sessionTracker.addSession(request.sessionId, to);

            /**
             *根据sessionId从sessionsById中获取到一个session，设置它的owner属性。
             */
            zks.setOwner(request.sessionId, request.getOwner());
            break;        
        ......
    }


    /**
    最终，server端会给client返回一个ConnectResponse对象，返回协商的sessionTimeout，唯一的sessionId和client的密码
    ConnectResponse rsp = new ConnectResponse(0, 
            valid ? cnxn.getSessionTimeout()  : 0, 
            valid ? cnxn.getSessionId() : 0,
            valid ? generatePasswd(cnxn.getSessionId()) : new byte[16]);
     */
    //-------------------------------------到此，server的处理就完毕了，接下来看client的处理---------------------




    //-------------------------------------------------客户端的处理------------------------------------------------
    /**
     *ClientCnxnSocketNIO类的doIO()方法中：
     */
    if (sockKey.isReadable()) {
        //先读包的长度，一个int  
        int rc = sock.read(incomingBuffer);
        if (rc < 0) {
            throw new EndOfStreamException(
                    "Unable to read additional data from server sessionid 0x"
                            + Long.toHexString(sessionId)
                            + ", likely server has closed socket");
        }
        //如果读满
        if (!incomingBuffer.hasRemaining()) {
            incomingBuffer.flip();
            if (incomingBuffer == lenBuffer) {
                recvCount++;
                readLength();          //给incomingBuffer分配包长度的空间
            } else if (!initialized) { //如果还未初始化，就是session还没建立
                readConnectResult();   //将incomingBuffer的内容反序列化成ConnectResponse对象
                enableRead();          //继续读后续响应
                //如果还有写请求，确保write事件ok
                if (findSendablePacket(outgoingQueue, cnxn.sendThread.clientTunneledAuthenticationInProgress()) != null) {
                    enableWrite();
                }
                //准备读下一个响应
                lenBuffer.clear();
                incomingBuffer = lenBuffer;
                updateLastHeard();
                /**
                 *session建立完毕
                 */
                initialized = true;
            } else {
                sendThread.readResponse(incomingBuffer);
                lenBuffer.clear();
                incomingBuffer = lenBuffer;
                updateLastHeard();
            }
        }
    }


    void readConnectResult() throws IOException {
        // .....
        ByteBufferInputStream bbis = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
        ConnectResponse conRsp = new ConnectResponse();
        /**
         *将incomingBuffer反序列化成ConnectResponse对象
         */
        conRsp.deserialize(bbia, "connect");

        boolean isRO = false;
        try {
            isRO = bbia.readBool("readOnly");
        } catch (IOException e) {
            LOG.warn("Connected to an old server; r-o mode will be unavailable");
        }

        /**
         *得到server端的sessionId，将服务端sessionId赋给ClientCnxnSocket对象的sessionId变量
         */
        this.sessionId = conRsp.getSessionId();

        // 调用SendThread类的onConnection方法
        sendThread.onConnected(conRsp.getTimeOut(), this.sessionId, conRsp.getPasswd(), isRO);
    }

    // 参数说明：服务端sessionTimeout、服务端sessionId、服务端生成的sessionPasswd、是否只读
    void onConnected(int _negotiatedSessionTimeout, long _sessionId, byte[] _sessionPasswd, boolean isRO) throws IOException {
        negotiatedSessionTimeout = _negotiatedSessionTimeout;
        if (negotiatedSessionTimeout <= 0) {
            state = States.CLOSED;
            eventThread.queueEvent(new WatchedEvent( Watcher.Event.EventType.None, Watcher.Event.KeeperState.Expired, null));
            eventThread.queueEventOfDeath();
            throw new SessionExpiredException( "Unable to reconnect to ZooKeeper service, session 0x" + Long.toHexString(sessionId) + " has expired");
        }
        if (!readOnly && isRO) {
            LOG.error("Read/write client got connected to read-only server");
        }

        /**
         *初始化client端的session相关参数
         *由服务端的sessionTimeout值计算得到客户端的readTimeout和connectTimeout
         *将服务端的sessionId、sessionPasswd赋给客户端的sessionId、sessionPasswd
         */
        readTimeout = negotiatedSessionTimeout * 2 / 3;
        connectTimeout = negotiatedSessionTimeout / hostProvider.size();
        hostProvider.onConnected();
        sessionId = _sessionId;
        sessionPasswd = _sessionPasswd;

        /**
         *在此处将state置为CONNECTEDREADONLY或CONNECTED状态
         */
        state = (isRO) ?  States.CONNECTEDREADONLY : States.CONNECTED;

        seenRwServerBefore |= !isRO;
        LOG.info("Session establishment complete on server "
                + clientCnxnSocket.getRemoteSocketAddress()
                + ", sessionid = 0x" + Long.toHexString(sessionId)
                + ", negotiated timeout = " + negotiatedSessionTimeout
                + (isRO ? " (READ-ONLY mode)" : ""));

        /**
         *触发一个SyncConnected事件
         *调用EventThread的queueEvent()方法，创建WatcherSetEventPair对象，并放到waitingEvents队列中
         *这样EventThread线程在循环时，就可以从waitingEvents队列中取出WatcherSetEventPair对象，调用Watcher集合中每一个watcher的process()做相应处理
         */
        KeeperState eventState = (isRO) ?  KeeperState.ConnectedReadOnly : KeeperState.SyncConnected;
        eventThread.queueEvent(new WatchedEvent( Watcher.Event.EventType.None, eventState, null));
    }
