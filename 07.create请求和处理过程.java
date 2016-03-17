    /**
     *--------------------------------------------------�ͻ��˵Ĵ���----------------------------------------------
     */
    /**
     *Zookeeper��
     */
    public String create(final String path, byte data[], List<ACL> acl, CreateMode createMode) throws KeeperException, InterruptedException {
        final String clientPath = path;
        PathUtils.validatePath(clientPath, createMode.isSequential());
        final String serverPath = prependChroot(clientPath);
        //��������ͷ
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.create);
        //���������塣 �������а�����Ҫ�����ڵ�����ݡ��ڵ�����͡��ڵ��·�����ڵ�ķ���Ȩ��
        CreateRequest request = new CreateRequest();
        request.setData(data);
        request.setFlags(createMode.toFlag());
        request.setPath(serverPath);
        if (acl != null && acl.size() == 0) {
            throw new KeeperException.InvalidACLException();
        }
        request.setAcl(acl);
        //������Ӧ��
        CreateResponse response = new CreateResponse();
        //����ClientCnxnSocketNIO��submitRequest()����������һ����Ӧͷ����
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
     *ClientCnxn��
     */
    public ReplyHeader submitRequest(RequestHeader h, Record request,Record response, WatchRegistration watchRegistration) throws InterruptedException {
        ReplyHeader r = new ReplyHeader();
        Packet packet = queuePacket(h, r, request, response, null, null, null,null, watchRegistration);
        synchronized (packet) {
            /**
             *��Packet�����finished������Ϊtrue֮ǰ����ǰ�߳�һֱ�����š���ͱ�ʾ�ͻ�������"ͬ��"�ķ�ʽ����zookeeper API���в�����
             */
            while (!packet.finished) {
                packet.wait();
            }
        }
        return r;
    }
    
    /**
     *ClientCnxn��
     */
    Packet queuePacket(RequestHeader h, ReplyHeader r, Record request, Record response, AsyncCallback cb, String clientPath, String serverPath, Object ctx, WatchRegistration watchRegistration) {
        Packet packet = null;
        synchronized (outgoingQueue) {
            /**
              *������Packet���󣬳�������ͷ����Ӧͷ�������塢��Ӧ���⼸�������⣬�����������null����false
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
                     *�����������Я���Ĳ�������ΪcloseSession����closing��Ϊtrue��
                     *SendThread�߳���ѭ�������У��жϵ�closingΪtrue�����˳�ѭ�����߳���ֹ
                     */
                    closing = true;
                }
                /**
                 *���ƴ���sessionʱ����outgoingQueue���������Packet����
                 */
                outgoingQueue.add(packet);
            }
        }
        //��ClientCnxnSocketNIO�У�����selector��������select()��������
        sendThread.getClientCnxnSocket().wakeupCnxn();
        return packet;
    }
    

    /**
      �������ͻ���Ҫ���ĺʹ���sessionʱһ������ClientCnxnSocketNIO���doIO()�����У����л����洴����Packet���󣬴���ByteBuffer����SocketChannel��д������
      */




    /**
     *---------------------------------------------------����˽ڵ�Ĵ���---------------------------------------------
     */
    //����˴�����̵�ǰ�����ʹ���session�Ĺ���һ������ͬ������Ϊsession�Ѿ������ˣ�����initializedΪtrue�����Խ��������õ���readRequest()������������readConnectRequest()������
    
    //ֱ�ӿ�processPacket()�����ɡ�  ����Request���󣬵���submitRequest����ʼ����server�˵�����ִ����
    public void processPacket(ServerCnxn cnxn, ByteBuffer incomingBuffer) throws IOException {
        InputStream bais = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bia = BinaryInputArchive.getArchive(bais);
        RequestHeader h = new RequestHeader();
        h.deserialize(bia, "header");
        incomingBuffer = incomingBuffer.slice();
        //......
        /**
         *Request����Ĳ���ֵ��
         *cnxn:NIOServerCnxn����
         *sessionId�������sessionId
         *xid���ͻ�������Id���ͻ��˷��͵���ҵ�����󣬳�ȥping��auth�����������������ֵ
         *type��create
         *request:incomingBuffer
         *authInfo
         */
        Request si = new Request(cnxn, cnxn.getSessionId(), h.getXid(), h.getType(), incomingBuffer, cnxn.getAuthInfo());
        si.setOwner(ServerCnxn.me);
        submitRequest(si);
        //......
    }


    /**
     *��һ����������PrepRequestProcesser

     *��������һ����������PrepRequestProcesser��pRequest2Txn()�Ĵ������
     *�����Ĳ���˵����
     *type��create
     *zxid������˵�zxid����0��ʼ����
     *request��Request����
     *record���յ�CreateRequest����
     *deserialize��true
     */
    protected void pRequest2Txn(int type, long zxid, Request request, Record record, boolean deserialize) throws KeeperException, IOException, RequestProcessorException {
        /**
         *����Request���������ͷhdr����
          hdr����������ֵ�У�
          clientId:server��sessionId��
          cxid:�ͻ��˵�����id��
          zxid:����˵�zxid��
          time:����˵�sessionTimeout��
          type:��������create
         */
        request.hdr = new TxnHeader(request.sessionId, request.cxid, zxid, zks.getTime(), type);

        switch (type) {
            case OpCode.create:         
                /**
                 *���session�Ƿ���Ч
                 */       
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
               /**
                *��incomingBuffer�����л����õ����������
                */ 
                CreateRequest createRequest = (CreateRequest)record;  
                if(deserialize)
                    ByteBufferInputStream.byteBuffer2Record(request.request, createRequest);
                /**
                 *���·�������Ƿ���Ч
                 */
                String path = createRequest.getPath();
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash == -1 || path.indexOf('\0') != -1 || failCreate) {
                    LOG.info("Invalid path " + path + " with session 0x" + Long.toHexString(request.sessionId));
                    throw new KeeperException.BadArgumentsException(path);
                }
                /**
                 *Ȩ��ȥ��
                 */
                List<ACL> listACL = removeDuplicates(createRequest.getAcl());
                if (!fixupACL(request.authInfo, listACL)) {
                    throw new KeeperException.InvalidACLException(path);
                }
                
                //�õ����ڵ��ChangeRecord��
                String parentPath = path.substring(0, lastSlash);
                ChangeRecord parentRecord = getRecordForPath(parentPath);

                /**
                 *Ȩ�޼��
                 */
                checkACL(zks, parentRecord.acl, ZooDefs.Perms.CREATE,request.authInfo);

                //�õ����ڵ���ӽڵ���´���
                int parentCVersion = parentRecord.stat.getCversion();

                //�õ������ڵ�����ͣ��־á���ʱ������
                //���������Ľڵ㣬��Ҫ�޸Ľڵ��·���������������ţ������ӽڵ�ĸ��´�������
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

                //���Ҫ�����Ľڵ��Ѿ����ڣ����׳��쳣
                try {
                    if (getRecordForPath(path) != null) {
                        throw new KeeperException.NodeExistsException(path);
                    }
                } catch (KeeperException.NoNodeException e) {
                }

                //���ڵ��ephemeralOwner���Բ�Ϊ0��˵�����ڵ�����ʱ�ڵ㣬��ʱ�ڵ㲻�ܴ����ӽڵ㣬�׳��쳣
                boolean ephemeralParent = parentRecord.stat.getEphemeralOwner() != 0;
                if (ephemeralParent) {
                    throw new KeeperException.NoChildrenForEphemeralsException(path);
                }

                //���¸��ڵ���ӽڵ���´���
                int newCversion = parentRecord.stat.getCversion()+1;

                /**
                 *����Request�����������txn
                 */
                request.txn = new CreateTxn(path, createRequest.getData(), listACL, createMode.isEphemeral(), newCversion);
                
                //���Ҫ�����Ľڵ�ĸ���ʱ�ڵ㣬���������Ӧ��StatPersisted�����ephemeralOwner����ֵΪ��ýڵ�󶨵�sessionId
                StatPersisted s = new StatPersisted();
                if (createMode.isEphemeral()) {
                    s.setEphemeralOwner(request.sessionId);
                }

                //���¸��ڵ��Ӧ��ChangeRecord�ṹ��Ĳ���ֵ
                parentRecord = parentRecord.duplicate(request.hdr.getZxid());
                parentRecord.childCount++;
                parentRecord.stat.setCversion(newCversion);

                //�����ڵ��changeRecord�͵�ǰ�����ڵ��changeRecord���浽outstandingChangesForPath������
                addChangeRecord(parentRecord);
                addChangeRecord(new ChangeRecord(request.hdr.getZxid(), path, s,0, listACL));
                break;
        }
    }
}

    
    /**
    �ڶ�����������SyncRequestProcessor�Ĵ�����Ҫ��logд�룬��֮ǰ�ķ������ƣ���׸��
    */

    
    /**
     *FinalRequestProcessor�����޸��ڴ��е�datatree�ṹ.
     */
    
    /**
     *DataTree��
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
                     *��������ͷ���������е�����ȥ�����ڵ�
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
     *DataTree��
     *��ʼ���������ڵ㡣ǰ�涼��һЩԤ����
     */
    public String createNode(String path, byte data[], List<ACL> acl, long ephemeralOwner, int parentCVersion, long zxid, long time) throws KeeperException.NoNodeException, KeeperException.NodeExistsException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        /**
         *����Ҫ�����ڵ�Ľڵ�״̬��������StatPersisted
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

        //��nodes�����еõ��ڵ�ĸ��ڵ�
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (parent) {
            Set<String> children = parent.getChildren();
            if (children != null) {
                //Ҫ�����Ľڵ��Ѿ������븸�ڵ�ĺ��ӽڵ㼯���У����׳��쳣
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
             *���촴���ڵ��DataNode����
             *�������ڵ��·���ַ�����ӵ����ڵ��children������
             *��DataNode��ӵ�nodes������
             *����һ�����ڵ���Ѿ����������  OK
             */
            DataNode child = new DataNode(parent, data, longval, stat);
            parent.addChild(childName);
            nodes.put(path, child);

            /**
             *��������Ľڵ��Ǹ���ʱ�ڵ㣬ͬʱ������ӵ�ephemerals������
             *ֻҪ����ͬһ���Ự�д�������ʱ�ڵ㣬��ephemerals���Ǿ�����ͬ��key����ΪsessionId
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
         *����NodeCreated��NodeChildrenChanged�¼� 
         *  ���Ǽ�ش����ڵ�·��NodeCreated�¼���watcher����ȥִ�����ǵ�process()����
         *  ���Ǽ�ظ��ڵ�NodeChildrenChanged�¼���watcher����ȥִ�����ǵ�process()����
         */
        dataWatches.triggerWatch(path, Event.EventType.NodeCreated);
        childWatches.triggerWatch(parentName.equals("") ? "/" : parentName, Event.EventType.NodeChildrenChanged);
        return path;
    }


    /**
     *-----------------------------------------------������¼��Ĵ���---------------------------------------------
     */
    /**
     *�ڵ���Ȼ��������ˣ���������Ӧ�¼�֪ͨ�Ĺ���
     *���յ���server��WatcherManager��
     */
    public Set<Watcher> triggerWatch(String path, EventType type, Set<Watcher> supress) {
        /**
         * �ɽڵ�·����Zookeeper״̬���¼����ͣ�����WatchedEvent����
         * �ں��棬����WatchedEvent�õ�WatcherEvent����Ȼ��ͨ����Ϣ���ݽ����ͻ��˵�EventThread��ȥ����
         */
        WatchedEvent e = new WatchedEvent(type,KeeperState.SyncConnected, path);

        HashSet<Watcher> watchers;
        synchronized (this) {
            /**
             *Watcher�ͽڵ��Ƕ�Զ�Ĺ�ϵ��
             *  һ���ڵ���Ա����Watcher��أ�
             *  һ��Watcher���Լ�ض���ڵ�
             */

            //ɾ�����õ���ش˽ڵ��watcher����
            watchers = watchTable.remove(path);
            if (watchers == null || watchers.isEmpty()) {
                if (LOG.isTraceEnabled()) {
                    ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK, "No watchers for " + path);
                }
                return null;
            }
            for (Watcher w : watchers) {
                //ÿһ��Watcher��ӵ��������صĽڵ㼯�ϣ��Ӽ�����ɾ����ǰ�ڵ�
                HashSet<String> paths = watch2Paths.get(w);
                if (paths != null) {
                    paths.remove(path);
                }
            }
            /**
             *ͨ�����������������Ա�֤Watcher��һ���Եġ�
             */
        }
        for (Watcher w : watchers) {
            if (supress != null && supress.contains(w)) {
                continue;
            }
            //����watcher���ϣ�ִ�����ǵ�process()����
            w.process(e);
        }
        return watchers;
    }

    /**
     *process()�����ľ��崦��
     *NIOServerCncx��
     */
    synchronized public void process(WatchedEvent event) {
        /**
         *������ӦͷReplyHeader
         */
        ReplyHeader h = new ReplyHeader(-1, -1L, 0);
        WatcherEvent e = event.getWrapper();
        //������Ӧ
        sendResponse(h, e, "notification");
    }

    /**
     *NIOServerCncx��
     */
    synchronized public void sendResponse(ReplyHeader h, Record r, String tag) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            /**
             *����Ӧͷ��WatcherEvent�������л����õ�ByteBuffer
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
             *��ͻ��˷�������
             */
            sendBuffer(bb);
            ......
         } catch(Exception e) {
            LOG.warn("Unexpected exception. Destruction averted.", e);
         }
    }



    /**
     *------------------------------------------server�˾ʹ������ˣ�������client��SendThread�յ���Ӧ-----------------------------------
     */
    /**
     *ClientCnxnSocketNIO��doIO()��SocketChannel��ȡ����˷���������
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
                //initializedΪtrue�����Ե���SendThread���readResponse()����
                sendThread.readResponse(incomingBuffer);
                lenBuffer.clear();
                incomingBuffer = lenBuffer;
                updateLastHeard();
            }
        }
    }

    /**
     *SendThread��readResponse()����
     */
    void readResponse(ByteBuffer incomingBuffer) throws IOException {
            ByteBufferInputStream bbis = new ByteBufferInputStream(incomingBuffer);
            BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
            /**
             *�����л��õ���ӦͷReplyHeader
             */
            ReplyHeader replyHdr = new ReplyHeader();
            replyHdr.deserialize(bbia, "header");
            ......
            //create�������ص���Ӧͷ��xid����ֵΪ-1
            if (replyHdr.getXid() == -1) {
                WatcherEvent event = new WatcherEvent();
                /**
                 *�����л��õ�WatcherEvent����
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
                 *��WatcherEvent���󽻸�EventThread�߳�ȥ����
                 */
                eventThread.queueEvent(we);
                return;
            }
    }



/*--------------------------------------------���Ͼ������create�����Ĵ���delete��set��get�����������Ƶ�-------------------------*/
