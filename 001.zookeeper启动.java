ZookeeperServerMain类
    protected void initializeAndRun(String[] args) throws ConfigException, IOException {
        try {
            ManagedUtil.registerLog4jMBeans();
        } catch (JMException e) {
            LOG.warn("Unable to register log4j JMX control", e);
        }

        ServerConfig config = new ServerConfig();
        if (args.length == 1) {
            config.parse(args[0]);
        } else {
            config.parse(args);
        }

        runFromConfig(config);
    }

    /**
     * 1.从配置文件zoo.cfg读取信息
     */
    public void parse(String path) throws ConfigException {
        QuorumPeerConfig config = new QuorumPeerConfig();
        config.parse(path);
        readFrom(config);
    }
    public void parse(String path) throws ConfigException {
        File configFile = new File(path);
        LOG.info("Reading configuration from: " + configFile);
        try {
            if (!configFile.exists()) {
                throw new IllegalArgumentException(configFile.toString() + " file is missing");
            }
            Properties cfg = new Properties();
            FileInputStream in = new FileInputStream(configFile);
            try {
                cfg.load(in);
            } finally {
                in.close();
            }
            parseProperties(cfg);
        } catch (IOException e) {
            throw new ConfigException("Error processing " + path, e);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Error processing " + path, e);
        }
    }

    /**
     * 启动
     */
    public void runFromConfig(ServerConfig config) throws IOException {
        LOG.info("Starting server");
        FileTxnSnapLog txnLog = null;
        try {
            // 创建ZookeeperServer对象，并将读取的配置信息赋给ZookeeperServer的属性
            ZooKeeperServer zkServer = new ZooKeeperServer();
            txnLog = new FileTxnSnapLog(new File(config.dataLogDir), new File(config.dataDir));
            zkServer.setTxnLogFactory(txnLog);
            zkServer.setTickTime(config.tickTime);
            zkServer.setMinSessionTimeout(config.minSessionTimeout);
            zkServer.setMaxSessionTimeout(config.maxSessionTimeout);

            // 默认创建得到NIOServerCnxnFactory类的实例
            cnxnFactory = ServerCnxnFactory.createFactory();
            /**
             * 创建ServerSocketChannel实例，注册到Selector上，感兴趣的操作为accept
             */
            cnxnFactory.configure(config.getClientPortAddress(),config.getMaxClientCnxns());


            cnxnFactory.startup(zkServer);
            cnxnFactory.join();
            if (zkServer.isRunning()) {
                zkServer.shutdown();
            }
        } catch (InterruptedException e) {
            LOG.warn("Server interrupted", e);
        } finally {
            if (txnLog != null) {
                txnLog.close();
            }
        }
    }


    public void startup(ZooKeeperServer zks) throws IOException,InterruptedException {
        /**
         * 2.启动IO主线程
         */
        start();

        /**
         * 3.从log和snapshot恢复database和session
         */
        zks.startdata();

        /**
         * 4.启动sessionTracker线程，初始化IO请求的处理链，并启动每个processor线程
         */
        zks.startup();
        setZooKeeperServer(zks);
    }

    public void run() {
        while (!ss.socket().isClosed()) {
            try {
                selector.select(1000);
                Set<SelectionKey> selected;
                synchronized (this) {
                    selected = selector.selectedKeys();
                }
                ArrayList<SelectionKey> selectedList = new ArrayList<SelectionKey>(selected);
                Collections.shuffle(selectedList);
                for (SelectionKey k : selectedList) {
                    if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                        SocketChannel sc = ((ServerSocketChannel) k .channel()).accept();
                        InetAddress ia = sc.socket().getInetAddress();
                        int cnxncount = getClientCnxnCount(ia);
                        if (maxClientCnxns > 0 && cnxncount >= maxClientCnxns){
                            LOG.warn("Too many connections from " + ia + " - max is " + maxClientCnxns );
                            sc.close();
                        } else {
                            LOG.info("Accepted socket connection from " + sc.socket().getRemoteSocketAddress());
                            sc.configureBlocking(false);
                            SelectionKey sk = sc.register(selector, SelectionKey.OP_READ);
                            /**
                             * 根据sc和sk创建NIOServerCnxn对象，并将它绑定到sk上
                             * 这样以后发生了读写操作时，都是调用NIOServerCnxn类里的doIO()方法进行处理
                             */
                            NIOServerCnxn cnxn = createConnection(sc, sk);
                            sk.attach(cnxn);
                            addCnxn(cnxn);
                        }
                    } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                        NIOServerCnxn c = (NIOServerCnxn) k.attachment();
                        c.doIO(k);
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Unexpected ops in select " + k.readyOps());
                        }
                    }
                }
                selected.clear();
            } catch (RuntimeException e) {
                LOG.warn("Ignoring unexpected runtime exception", e);
            } catch (Exception e) {
                LOG.warn("Ignoring exception", e);
            }
        }
        closeAll();
        LOG.info("NIOServerCnxn factory exited run method");
    }


    public void startdata() throws IOException, InterruptedException {
        if (zkDb == null) {
            zkDb = new ZKDatabase(this.txnLogFactory);
        }  
        if (!zkDb.isInitialized()) {
            loadData();
        }
    }
    // '/','/zookeeper','/zookeeper/quota'3个系统节点初始化
    public DataTree() {
        nodes.put("", root);
        nodes.put(rootZookeeper, root);

        root.addChild(procChildZookeeper);
        nodes.put(procZookeeper, procDataNode);

        procDataNode.addChild(quotaChildZookeeper);
        nodes.put(quotaZookeeper, quotaDataNode);
    }
    /**
     *  Restore sessions and data
     */
    public void loadData() throws IOException, InterruptedException {
        // 执行恢复，并返回最新的事务ID
        if(zkDb.isInitialized()){
            setZxid(zkDb.getDataTreeLastProcessedZxid());
        }
        else {
            setZxid(zkDb.loadDataBase());
        }
        
        // 清理session
        LinkedList<Long> deadSessions = new LinkedList<Long>();
        for (Long session : zkDb.getSessions()) {
            if (zkDb.getSessionWithTimeOuts().get(session) == null) {
                deadSessions.add(session);
            }
        }
        zkDb.setDataTreeInit(true);
        for (long session : deadSessions) {
            killSession(session, zkDb.getDataTreeLastProcessedZxid());
        }
    }
<-------结束了----->    
