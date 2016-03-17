Zookeeper Server持久化两类数据:Transaction以及Snapshot.
    Transaction数据就是Request对象中的hdr(事务头)和txn(事务体)
    Snapshot数据就是DataTree(节点数据)和Session信息
logDir存储transaction命令，dataDir存储snap快照，其下子目录名称以version-2命名，子目录内部文件是分别以log.zxid和snapshot.lastProcessedZxid命名，每个目录下可以有很多个这样的文件，
Transaction文件的文件名中zxid是文件中所有命令中zxid最小的zxid,而Snapshot中的lastProcessedZxid是最后一个操作的zxid，一般来讲是最大的zxid。


    /**
     *SyncRequestProcesser类
     *queuedRequests队列负责：接受上一个processer传过来的Request对象
     *toFlush队列负责：根据系统压力大小，如果系统压力大，则积压Request对象
     */
    public void run() {
        try {
            int logCount = 0;
            setRandRoll(r.nextInt(snapCount/2));
            /**
             *flush()操作的频率： 
             *  如果系统压力小(queuedRequests队列中无Request对象)，则只要toFlush不为空，就进行flush()批处理操作；
             *  如果系统压力大(queuedRequests队列中一直有Request对象)，则将Request对象积压在toFlush队列中，当积压的请求数超过1000之后，进行flush()批处理操作
             */
            while (true) {
                Request si = null;
                if (toFlush.isEmpty()) {        
                    si = queuedRequests.take();
                } else {
                    si = queuedRequests.poll(); 
                    if (si == null) {
                        flush(toFlush);          
                        continue;
                    }
                }
                if (si == requestOfDeath) {
                    break;
                }
                if (si != null) {
                    /**
                     *将Request中的事务头和事务体append到log输出流，先序列化再append，注意此时request还没flush到磁盘，还在内存
                     *同时将带有事务信息的Request对象放到toFlush队列中
                     *真正将事务信息刷新到磁盘上操作，是在flush(toFlush)方法中
                     */
                    if (zks.getZKDatabase().append(si)) {
                        //成功计数器
                        logCount++;
                        //如果成功append的request累计数量大于某个值，则执行flush log的操作
                        if (logCount > (snapCount / 2 + randRoll)) {
                            randRoll = r.nextInt(snapCount/2);
                            zks.getZKDatabase().rollLog();
                            if (snapInProcess != null && snapInProcess.isAlive()) {
                                LOG.warn("Too busy to snap, skipping");
                            } else {
                                /**
                                 *启动一个线程异步将内存里的Database和session状态写入到snapshot文件
                                 */
                                snapInProcess = new Thread("Snapshot Thread") {
                                        public void run() {
                                            try {
                                                zks.takeSnapshot();
                                            } catch(Exception e) {
                                                LOG.warn("Unexpected exception", e);
                                            }
                                        }
                                    };
                                snapInProcess.start();
                            }
                            logCount = 0;
                        }
                    } else if (toFlush.isEmpty()) {
                        /**
                         *在将Request中的事务头和事务体append到log输出流失败时才会进入到这个if分支，那什么时候会append(request)会返回false呢？
                         *Request对象中请求头hrd为null时会返回false。  如果客户端是连接服务端请求，会没有事务头
                         *没有携带事务信息的Request对象，不会写日志数据，也不会写快照数据，仅仅去执行后面的processer
                         */
                        if (nextProcessor != null) {
                            nextProcessor.processRequest(si);
                            if (nextProcessor instanceof Flushable) {
                                ((Flushable)nextProcessor).flush();
                            }
                        }
                        continue;
                    }
                    //将从queuedRequests队列中取出的Request对象放到toFlush队列中，以备后续批处理
                    toFlush.add(si);
                    if (toFlush.size() > 1000) {
                        flush(toFlush);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.error("Severe unrecoverable error, exiting", t);
            running = false;
            System.exit(11);
        }
        LOG.info("SyncRequestProcessor exited!");
    }

    private void flush(LinkedList<Request> toFlush) throws IOException, RequestProcessorException {
        if (toFlush.isEmpty())
            return;
        /**
         *将之前的append log flush到磁盘，并顺便关闭旧的log文件句柄
         */
        zks.getZKDatabase().commit();

        //log flush完后，开始处理flush队列里的Request
        while (!toFlush.isEmpty()) {
            Request i = toFlush.remove();
            /**
             *执行后面的processor.通常情况下是FinalRequestProcessor
             */
            if (nextProcessor != null) {
                nextProcessor.processRequest(i);
            }
        }
        if (nextProcessor != null && nextProcessor instanceof Flushable) {
            ((Flushable)nextProcessor).flush();
        }
    }


ZKDatabase类：
    public boolean append(Request si) throws IOException {
        //snapLog：FileTxnSnapLog
        return this.snapLog.append(si);
    }

FileTxnSnapLog类：
    public boolean append(Request si) throws IOException {
        //txnLog：TxnLog
        return txnLog.append(si.hdr, si.txn);
    }

TxnLog的实现类FileTxnLog：
    public synchronized boolean append(TxnHeader hdr, Record txn) throws IOException {
        if (hdr != null) {
            if (hdr.getZxid() <= lastZxidSeen) {
                LOG.warn("Current zxid " + hdr.getZxid() + " is <= " + lastZxidSeen + " for " + hdr.getType());
            }
            if (logStream==null) {
               if(LOG.isInfoEnabled()){
                    LOG.info("Creating new log file: log." + Long.toHexString(hdr.getZxid()));
               }
               
               logFileWrite = new File(logDir, ("log." + Long.toHexString(hdr.getZxid())));
               fos = new FileOutputStream(logFileWrite);
               logStream=new BufferedOutputStream(fos);
               oa = BinaryOutputArchive.getArchive(logStream);
               FileHeader fhdr = new FileHeader(TXNLOG_MAGIC,VERSION, dbId);
               fhdr.serialize(oa, "fileheader");
               // Make sure that the magic number is written before padding.
               logStream.flush();
               currentSize = fos.getChannel().position();
               streamsToFlush.add(fos);
            }
            padFile(fos);
            byte[] buf = Util.marshallTxnEntry(hdr, txn);
            if (buf == null || buf.length == 0) {
                throw new IOException("Faulty serialization for header " + "and txn");
            }
            Checksum crc = makeChecksumAlgorithm();
            crc.update(buf, 0, buf.length);
            oa.writeLong(crc.getValue(), "txnEntryCRC");
            Util.writeTxnBytes(oa, buf);
            
            return true;
        }
        return false;
    }
