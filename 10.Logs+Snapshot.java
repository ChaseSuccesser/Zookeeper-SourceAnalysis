Zookeeper Server�־û���������:Transaction�Լ�Snapshot.
    Transaction���ݾ���Request�����е�hdr(����ͷ)��txn(������)
    Snapshot���ݾ���DataTree(�ڵ�����)��Session��Ϣ
logDir�洢transaction���dataDir�洢snap���գ�������Ŀ¼������version-2��������Ŀ¼�ڲ��ļ��Ƿֱ���log.zxid��snapshot.lastProcessedZxid������ÿ��Ŀ¼�¿����кܶ���������ļ���
Transaction�ļ����ļ�����zxid���ļ�������������zxid��С��zxid,��Snapshot�е�lastProcessedZxid�����һ��������zxid��һ������������zxid��


    /**
     *SyncRequestProcesser��
     *queuedRequests���и��𣺽�����һ��processer��������Request����
     *toFlush���и��𣺸���ϵͳѹ����С�����ϵͳѹ�������ѹRequest����
     */
    public void run() {
        try {
            int logCount = 0;
            setRandRoll(r.nextInt(snapCount/2));
            /**
             *flush()������Ƶ�ʣ� 
             *  ���ϵͳѹ��С(queuedRequests��������Request����)����ֻҪtoFlush��Ϊ�գ��ͽ���flush()�����������
             *  ���ϵͳѹ����(queuedRequests������һֱ��Request����)����Request�����ѹ��toFlush�����У�����ѹ������������1000֮�󣬽���flush()���������
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
                     *��Request�е�����ͷ��������append��log������������л���append��ע���ʱrequest��ûflush�����̣������ڴ�
                     *ͬʱ������������Ϣ��Request����ŵ�toFlush������
                     *������������Ϣˢ�µ������ϲ���������flush(toFlush)������
                     */
                    if (zks.getZKDatabase().append(si)) {
                        //�ɹ�������
                        logCount++;
                        //����ɹ�append��request�ۼ���������ĳ��ֵ����ִ��flush log�Ĳ���
                        if (logCount > (snapCount / 2 + randRoll)) {
                            randRoll = r.nextInt(snapCount/2);
                            zks.getZKDatabase().rollLog();
                            if (snapInProcess != null && snapInProcess.isAlive()) {
                                LOG.warn("Too busy to snap, skipping");
                            } else {
                                /**
                                 *����һ���߳��첽���ڴ����Database��session״̬д�뵽snapshot�ļ�
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
                         *�ڽ�Request�е�����ͷ��������append��log�����ʧ��ʱ�Ż���뵽���if��֧����ʲôʱ���append(request)�᷵��false�أ�
                         *Request����������ͷhrdΪnullʱ�᷵��false��  ����ͻ��������ӷ�������󣬻�û������ͷ
                         *û��Я��������Ϣ��Request���󣬲���д��־���ݣ�Ҳ����д�������ݣ�����ȥִ�к����processer
                         */
                        if (nextProcessor != null) {
                            nextProcessor.processRequest(si);
                            if (nextProcessor instanceof Flushable) {
                                ((Flushable)nextProcessor).flush();
                            }
                        }
                        continue;
                    }
                    //����queuedRequests������ȡ����Request����ŵ�toFlush�����У��Ա�����������
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
         *��֮ǰ��append log flush�����̣���˳��رվɵ�log�ļ����
         */
        zks.getZKDatabase().commit();

        //log flush��󣬿�ʼ����flush�������Request
        while (!toFlush.isEmpty()) {
            Request i = toFlush.remove();
            /**
             *ִ�к����processor.ͨ���������FinalRequestProcessor
             */
            if (nextProcessor != null) {
                nextProcessor.processRequest(i);
            }
        }
        if (nextProcessor != null && nextProcessor instanceof Flushable) {
            ((Flushable)nextProcessor).flush();
        }
    }


ZKDatabase�ࣺ
    public boolean append(Request si) throws IOException {
        //snapLog��FileTxnSnapLog
        return this.snapLog.append(si);
    }

FileTxnSnapLog�ࣺ
    public boolean append(Request si) throws IOException {
        //txnLog��TxnLog
        return txnLog.append(si.hdr, si.txn);
    }

TxnLog��ʵ����FileTxnLog��
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
