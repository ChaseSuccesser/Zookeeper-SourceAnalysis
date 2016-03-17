    /**
     *--------------------------------------------------客户端的处理----------------------------------------------
     */
    /**
     *Zookeeper类
     */
    public String create(final String path, byte data[], List<ACL> acl, CreateMode createMode) throws KeeperException, InterruptedException {
        final String clientPath = path;
        PathUtils.validatePath(clientPath, createMode.isSequential());
        final String serverPath = prependChroot(clientPath);
        //创建请求头
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.create);
        //创建请求体。 请求体中包含：要创建节点的数据、节点的类型、节点的路径、节点的访问权限
        CreateRequest request = new CreateRequest();
        request.setData(data);
        request.setFlags(createMode.toFlag());
        request.setPath(serverPath);
        if (acl != null && acl.size() == 0) {
            throw new KeeperException.InvalidACLException();
        }
        request.setAcl(acl);
        //创建响应体
        CreateResponse response = new CreateResponse();
        //调用ClientCnxnSocketNIO的submitRequest()方法，返回一个响应头对象
        ReplyHeader r = cnxn.submitRequest(h, request, response, null);
        if (r.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(r.getErr()), clientPath);
        }
        if (cnxn.chrootPath == null) {
            return response.getPath();
        } else {
            return response.getPath().substring(cnxn.chrootPath.length());
        }
    }

    /**
     *ClientCnxn类
     */
    public ReplyHeader submitRequest(RequestHeader h, Record request,Record response, WatchRegistration watchRegistration) throws InterruptedException {
        ReplyHeader r = new ReplyHeader();
        Packet packet = queuePacket(h, r, request, response, null, null, null,null, watchRegistration);
        synchronized (packet) {
            /**
             *在Packet对象的finished变量变为true之前，当前线程一直阻塞着。这就表示客户端是以"同步"的方式调用zookeeper API进行操作。
             */
            while (!packet.finished) {
                packet.wait();
            }
        }
        return r;
    }
    
    /**
     *ClientCnxn类
     */
    Packet queuePacket(RequestHeader h, ReplyHeader r, Record request, Record response, AsyncCallback cb, String clientPath, String serverPath, Object ctx, WatchRegistration watchRegistration) {
        Packet packet = null;
        synchronized (outgoingQueue) {
            /**
              *创建的Packet对象，除了请求头、响应头、请求体、响应体这几个参数外，其余参数都是null或者false
              */
            packet = new Packet(h, r, request, response, watchRegistration);
            packet.cb = cb;
            packet.ctx = ctx;
            packet.clientPath = clientPath;
            packet.serverPath = serverPath;
            if (!state.isAlive() || closing) {
                conLossPacket(packet);
            } else {
                if (h.getType() == OpCode.closeSession) {
                    /**
                     *如果请求行中携带的操作类型为closeSession，则将closing置为true，
                     *SendThread线程在循环过程中，判断到closing为true，则退出循环，线程终止
                     */
                    closing = true;
                }
                /**
                 *类似创建session时，往outgoingQueue队列中添加Packet对象
                 */
                outgoingQueue.add(packet);
            }
        }
        //在ClientCnxnSocketNIO中，将在selector上阻塞的select()立即返回
        sendThread.getClientCnxnSocket().wakeupCnxn();
        return packet;
    }
    

    /**
      接下来客户端要做的和创建session时一样，在ClientCnxnSocketNIO类的doIO()方法中，序列化上面创建的Packet对象，创建ByteBuffer，向SocketChannel中写入数据
      */




    /**
     *---------------------------------------------------服务端节点的创建---------------------------------------------
     */
    //服务端处理过程的前两步和创建session的过程一样，不同的是因为session已经创建了，所以initialized为true，所以接下来调用的是readRequest()方法，而不是readConnectRequest()方法。
    
    //直接看processPacket()方法吧。  创建Request对象，调用submitRequest，开始进入server端的请求执行链
    public void processPacket(ServerCnxn cnxn, ByteBuffer incomingBuffer) throws IOException {
        InputStream bais = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bia = BinaryInputArchive.getArchive(bais);
        RequestHeader h = new RequestHeader();
        h.deserialize(bia, "header");
        incomingBuffer = incomingBuffer.slice();
        //......
        /**
         *Request对象的参数值：
         *cnxn:NIOServerCnxn对象
         *sessionId：服务端sessionId
         *xid：客户端事务Id。客户端发送的是业务请求，除去ping、auth操作，这个变量就有值
         *type：create
         *request:incomingBuffer
         *authInfo
         */
        Request si = new Request(cnxn, cnxn.getSessionId(), h.getXid(), h.getType(), incomingBuffer, cnxn.getAuthInfo());
        si.setOwner(ServerCnxn.me);
        submitRequest(si);
        //......
    }


    /**
     *第一个请求处理器PrepRequestProcesser

     *来看看第一个请求处理器PrepRequestProcesser的pRequest2Txn()的处理过程
     *方法的参数说明：
     *type：create
     *zxid：服务端的zxid，从0开始自增
     *request：Request对象
     *record：空的CreateRequest对象
     *deserialize：true
     */
    protected void pRequest2Txn(int type, long zxid, Request request, Record record, boolean deserialize) throws KeeperException, IOException, RequestProcessorException {
        /**
         *构造Request对象的事务头hdr变量
          hdr变量包含的值有：
          clientId:server端sessionId、
          cxid:客户端的事务id、
          zxid:服务端的zxid、
          time:服务端的sessionTimeout、
          type:操作类型create
         */
        request.hdr = new TxnHeader(request.sessionId, request.cxid, zxid, zks.getTime(), type);

        switch (type) {
            case OpCode.create:         
                /**
                 *检查session是否还有效
                 */       
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
               /**
                *从incomingBuffer反序列化，得到请求体对象
                */ 
                CreateRequest createRequest = (CreateRequest)record;  
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, createRequest);
                /**
                 *检查路径规则是否有效
                 */
                String path = createRequest.getPath();
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash == -1 || path.indexOf('\0') != -1 || failCreate) {
                    LOG.info("Invalid path " + path + " with session 0x" + Long.toHexString(request.sessionId));
                    throw new KeeperException.BadArgumentsException(path);
                }
                /**
                 *权限去重
                 */
                List<ACL> listACL = removeDuplicates(createRequest.getAcl());
                if (!fixupACL(request.authInfo, listACL)) {
                    throw new KeeperException.InvalidACLException(path);
                }
                
                //得到父节点的ChangeRecord。
                String parentPath = path.substring(0, lastSlash);
                ChangeRecord parentRecord = getRecordForPath(parentPath);

                /**
                 *权限检查
                 */
                checkACL(zks, parentRecord.acl, ZooDefs.Perms.CREATE,request.authInfo);

                //得到父节点的子节点更新次数
                int parentCVersion = parentRecord.stat.getCversion();

                //得到创建节点的类型：持久、临时、有序
                //如果是有序的节点，需要修改节点的路径，加上有序的序号，根据子节点的更新次数得来
                CreateMode createMode = CreateMode.fromFlag(createRequest.getFlags());
                if (createMode.isSequential()) {
                    path = path + String.format(Locale.ENGLISH, "%010d", parentCVersion);
                }

                try {
                    PathUtils.validatePath(path);
                } catch(IllegalArgumentException ie) {
                    LOG.info("Invalid path " + path + " with session 0x" + Long.toHexString(request.sessionId));
                    throw new KeeperException.BadArgumentsException(path);
                }

                //如果要创建的节点已经存在，则抛出异常
                try {
                    if (getRecordForPath(path) != null) {
                        throw new KeeperException.NodeExistsException(path);
                    }
                } catch (KeeperException.NoNodeException e) {
                }

                //父节点的ephemeralOwner属性不为0，说明父节点是临时节点，临时节点不能创建子节点，抛出异常
                boolean ephemeralParent = parentRecord.stat.getEphemeralOwner() != 0;
                if (ephemeralParent) {
                    throw new KeeperException.NoChildrenForEphemeralsException(path);
                }

                //更新父节点的子节点更新次数
                int newCversion = parentRecord.stat.getCversion()+1;

                /**
                 *构造Request对象的事务体txn
                 */
                request.txn = new CreateTxn(path, createRequest.getData(), listACL, createMode.isEphemeral(), newCversion);
                
                //如果要创建的节点的个临时节点，则设置其对应的StatPersisted对象的ephemeralOwner变量值为与该节点绑定的sessionId
                StatPersisted s = new StatPersisted();
                if (createMode.isEphemeral()) {
                    s.setEphemeralOwner(request.sessionId);
                }

                //更新父节点对应的ChangeRecord结构体的参数值
                parentRecord = parentRecord.duplicate(request.hdr.getZxid());
                parentRecord.childCount++;
                parentRecord.stat.setCversion(newCversion);

                //将父节点的changeRecord和当前创建节点的changeRecord保存到outstandingChangesForPath集合中
                addChangeRecord(parentRecord);
                addChangeRecord(new ChangeRecord(request.hdr.getZxid(), path, s,0, listACL));
                break;
        }
    }
}

    
    /**
    第二个请求处理器SyncRequestProcessor的处理，主要是log写入，和之前的分析类似，不赘述
    */

    
    /**
     *FinalRequestProcessor处理，修改内存中的datatree结构.
     */
    
    /**
     *DataTree类
     */
    public ProcessTxnResult processTxn(TxnHeader header, Record txn) {
        ProcessTxnResult rc = new ProcessTxnResult();
        try {
            rc.clientId = header.getClientId();
            rc.cxid = header.getCxid();
            rc.zxid = header.getZxid();
            rc.type = header.getType();
            rc.err = 0;
            rc.multiResult = null;
            switch (header.getType()) {
                case OpCode.create:
                    CreateTxn createTxn = (CreateTxn) txn;
                    rc.path = createTxn.getPath();
                    /**
                     *根据事务头和事务体中的数据去创建节点
                     */
                    createNode(
                            createTxn.getPath(),
                            createTxn.getData(),
                            createTxn.getAcl(),
                            createTxn.getEphemeral() ? header.getClientId() : 0,
                            createTxn.getParentCVersion(),
                            header.getZxid(), header.getTime());
                    break;

            //......
            }
        }
        return rc;
    }

    
    /**
     *DataTree类
     *开始真正创建节点。前面都是一些预处理
     */
    public String createNode(String path, byte data[], List<ACL> acl, long ephemeralOwner, int parentCVersion, long zxid, long time) throws KeeperException.NoNodeException, KeeperException.NodeExistsException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        /**
         *构造要创建节点的节点状态数据类型StatPersisted
         */
        StatPersisted stat = new StatPersisted();
        stat.setCtime(time);
        stat.setMtime(time);
        stat.setCzxid(zxid);
        stat.setMzxid(zxid);
        stat.setPzxid(zxid);
        stat.setVersion(0);
        stat.setAversion(0);
        stat.setEphemeralOwner(ephemeralOwner);

        //从nodes集合中得到节点的父节点
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (parent) {
            Set<String> children = parent.getChildren();
            if (children != null) {
                //要创建的节点已经存在与父节点的孩子节点集合中，则抛出异常
                if (children.contains(childName)) {
                    throw new KeeperException.NodeExistsException();
                }
            }
            
            if (parentCVersion == -1) {
                parentCVersion = parent.stat.getCversion();
                parentCVersion++;
            }    
            parent.stat.setCversion(parentCVersion);
            parent.stat.setPzxid(zxid);
            Long longval = convertAcls(acl);

            /**
             *构造创建节点的DataNode对象
             *将创建节点的路径字符串添加到父节点的children集合中
             *将DataNode添加到nodes集合中
             *到这一步，节点就已经创建完成了  OK
             */
            DataNode child = new DataNode(parent, data, longval, stat);
            parent.addChild(childName);
            nodes.put(path, child);

            /**
             *如果创建的节点是个临时节点，同时将它添加到ephemerals集合中
             *只要是在同一个会话中创建的临时节点，在ephemerals它们具有相同的key，都为sessionId
             */
            if (ephemeralOwner != 0) {
                HashSet<String> list = ephemerals.get(ephemeralOwner);
                if (list == null) {
                    list = new HashSet<String>();
                    ephemerals.put(ephemeralOwner, list);
                }
                synchronized (list) {
                    list.add(path);
                }
            }
        }
        ......
        /**
         *触发NodeCreated和NodeChildrenChanged事件 
         *  凡是监控创建节点路径NodeCreated事件的watcher，都去执行它们的process()方法
         *  凡是监控父节点NodeChildrenChanged事件的watcher，都去执行它们的process()方法
         */
        dataWatches.triggerWatch(path, Event.EventType.NodeCreated);
        childWatches.triggerWatch(parentName.equals("") ? "/" : parentName, Event.EventType.NodeChildrenChanged);
        return path;
    }


    /**
     *-----------------------------------------------服务端事件的处理---------------------------------------------
     */
    /**
     *节点虽然创建完成了，但还有相应事件通知的过程
     *最终调用server端WatcherManager类
     */
    public Set<Watcher> triggerWatch(String path, EventType type, Set<Watcher> supress) {
        /**
         * 由节点路径、Zookeeper状态、事件类型，构造WatchedEvent对象
         * 在后面，会由WatchedEvent得到WatcherEvent对象，然后通过信息传递交给客户端的EventThread类去处理
         */
        WatchedEvent e = new WatchedEvent(type,KeeperState.SyncConnected, path);

        HashSet<Watcher> watchers;
        synchronized (this) {
            /**
             *Watcher和节点是多对多的关系：
             *  一个节点可以被多个Watcher监控；
             *  一个Watcher可以监控多个节点
             */

            //删除并得到监控此节点的watcher集合
            watchers = watchTable.remove(path);
            if (watchers == null || watchers.isEmpty()) {
                if (LOG.isTraceEnabled()) {
                    ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK, "No watchers for " + path);
                }
                return null;
            }
            for (Watcher w : watchers) {
                //每一个Watcher都拥有它所监控的节点集合，从集合中删除当前节点
                HashSet<String> paths = watch2Paths.get(w);
                if (paths != null) {
                    paths.remove(path);
                }
            }
            /**
             *通过上面两步处理，可以保证Watcher是一次性的。
             */
        }
        for (Watcher w : watchers) {
            if (supress != null && supress.contains(w)) {
                continue;
            }
            //迭代watcher集合，执行它们的process()方法
            w.process(e);
        }
        return watchers;
    }

    /**
     *process()方法的具体处理
     *NIOServerCncx类
     */
    synchronized public void process(WatchedEvent event) {
        /**
         *创建响应头ReplyHeader
         */
        ReplyHeader h = new ReplyHeader(-1, -1L, 0);
        WatcherEvent e = event.getWrapper();
        //发送响应
        sendResponse(h, e, "notification");
    }

    /**
     *NIOServerCncx类
     */
    synchronized public void sendResponse(ReplyHeader h, Record r, String tag) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            /**
             *将响应头和WatcherEvent对象序列化，得到ByteBuffer
             */
            try {
                baos.write(fourBytes);
                bos.writeRecord(h, "header");
                if (r != null) {
                    bos.writeRecord(r, tag);
                }
                baos.close();
            } catch (IOException e) {
                LOG.error("Error serializing response");
            }
            byte b[] = baos.toByteArray();
            ByteBuffer bb = ByteBuffer.wrap(b);
            bb.putInt(b.length - 4).rewind();
            /**
             *向客户端发送数据
             */
            sendBuffer(bb);
            ......
         } catch(Exception e) {
            LOG.warn("Unexpected exception. Destruction averted.", e);
         }
    }



    /**
     *------------------------------------------server端就处理完了，接下来client端SendThread收到响应-----------------------------------
     */
    /**
     *ClientCnxnSocketNIO类doIO()从SocketChannel读取服务端发来的数据
     */
    if (sockKey.isReadable()) {
        int rc = sock.read(incomingBuffer);
        if (rc < 0) {
            throw new EndOfStreamException( "Unable to read additional data from server sessionid 0x" + Long.toHexString(sessionId) + ", likely server has closed socket");
        }
        if (!incomingBuffer.hasRemaining()) {
            incomingBuffer.flip();
            if (incomingBuffer == lenBuffer) {
                recvCount++;
                readLength();
            } else if (!initialized) {
                readConnectResult();
                enableRead();
                if (findSendablePacket(outgoingQueue, cnxn.sendThread.clientTunneledAuthenticationInProgress()) != null) {
                    enableWrite();
                }
                lenBuffer.clear();
                incomingBuffer = lenBuffer;
                updateLastHeard();
                initialized = true;
            } else {
                //initialized为true，所以调用SendThread类的readResponse()方法
                sendThread.readResponse(incomingBuffer);
                lenBuffer.clear();
                incomingBuffer = lenBuffer;
                updateLastHeard();
            }
        }
    }

    /**
     *SendThread类readResponse()方法
     */
    void readResponse(ByteBuffer incomingBuffer) throws IOException {
            ByteBufferInputStream bbis = new ByteBufferInputStream(incomingBuffer);
            BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
            /**
             *反序列化得到响应头ReplyHeader
             */
            ReplyHeader replyHdr = new ReplyHeader();
            replyHdr.deserialize(bbia, "header");
            ......
            //create操作返回的响应头的xid属性值为-1
            if (replyHdr.getXid() == -1) {
                WatcherEvent event = new WatcherEvent();
                /**
                 *反序列化得到WatcherEvent对象
                 */
                event.deserialize(bbia, "response");

                if (chrootPath != null) {
                    String serverPath = event.getPath();
                    if(serverPath.compareTo(chrootPath)==0)
                        event.setPath("/");
                    else if (serverPath.length() > chrootPath.length())
                        event.setPath(serverPath.substring(chrootPath.length()));
                    else {
                    	LOG.warn("Got server path " + event.getPath() + " which is too short for chroot path " + chrootPath);
                    }
                }

                WatchedEvent we = new WatchedEvent(event);
                
                /**
                 *将WatcherEvent对象交给EventThread线程去处理
                 */
                eventThread.queueEvent(we);
                return;
            }
    }



/*--------------------------------------------以上就完成了create操作的处理，delete，set，get操作都是类似的-------------------------*/
