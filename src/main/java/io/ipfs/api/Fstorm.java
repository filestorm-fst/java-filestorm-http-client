package io.ipfs.api;

import io.ipfs.api.utils.FileUtils;
import io.ipfs.cid.*;
import io.ipfs.multihash.Multihash;
import io.ipfs.multiaddr.MultiAddress;
import io.reactivex.Flowable;
import org.storm3j.crypto.Credentials;
import org.storm3j.crypto.RawTransaction;
import org.storm3j.crypto.TransactionEncoder;
import org.storm3j.protocol.Storm3j;
import org.storm3j.protocol.core.DefaultBlockParameter;
import org.storm3j.protocol.core.DefaultBlockParameterName;
import org.storm3j.protocol.core.Request;
import org.storm3j.protocol.core.methods.request.ShhFilter;
import org.storm3j.protocol.core.methods.request.Transaction;
import org.storm3j.protocol.core.methods.response.*;
import org.storm3j.protocol.http.HttpService;
import org.storm3j.protocol.websocket.events.LogNotification;
import org.storm3j.protocol.websocket.events.NewHeadsNotification;
import org.storm3j.tx.gas.DefaultGasProvider;
import org.storm3j.utils.Convert;
import org.storm3j.utils.Numeric;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Fstorm implements Storm3j{

    public static final Version MIN_VERSION = Version.parse("0.4.11");



    public enum PinType {all, direct, indirect, recursive}
    public List<String> ObjectTemplates = Arrays.asList("unixfs-dir");
    public List<String> ObjectPatchTypes = Arrays.asList("add-link", "rm-link", "set-data", "append-data");
    private static final int DEFAULT_TIMEOUT = 0;

    public final String host;
    public final int port;
    public final String protocol;
    private final String version;
    private int timeout = DEFAULT_TIMEOUT;
    public final Key key = new Key();
    public final Pin pin = new Pin();
    public final Repo repo = new Repo();
    public final FstormObject object = new FstormObject();
    public final Swarm swarm = new Swarm();
    public final Bootstrap bootstrap = new Bootstrap();
    public final Block block = new Block();
    public final Dag dag = new Dag();
    public final Diag diag = new Diag();
    public final Config config = new Config();
    public final Refs refs = new Refs();
    public final Update update = new Update();
    public final DHT dht = new DHT();
    public final File file = new File();
    public final Stats stats = new Stats();
    public final Name name = new Name();
    public final Pubsub pubsub = new Pubsub();
    private  Storm3j storm3j ;
    private Credentials credentials;
    public void setStorm3j(String fstUrl){
        this.storm3j = Storm3j.build(new HttpService(fstUrl));
    }
    public Storm3j getStorm3j(){
        return storm3j;
    }
    public void setCredentials(String privateKey){
        this.credentials = Credentials.create(privateKey);
    }

    public Credentials getCredentials(){
        return credentials;
    }

    public Fstorm(String host, int port,String fstUrl,String privateKey) {
        this(host, port, "/api/v0/", false,fstUrl,privateKey);
    }

    public Fstorm(String multiaddr,String fstUrl,String privateKey) {
        this(new MultiAddress(multiaddr),fstUrl,privateKey);
    }

    public Fstorm(MultiAddress addr,String fstUrl,String privateKey) {
        this(addr.getHost(), addr.getTCPPort(), "/api/v0/", detectSSL(addr),fstUrl,privateKey);
    }

    public Fstorm(String host, int port, String version, boolean ssl,String fstUrl,String privateKey) {
        this.host = host;
        this.port = port;

        if(ssl) {
            this.protocol = "https";
        } else {
            this.protocol = "http";
        }

        this.version = version;
        // Check Fstorm is sufficiently recent
        try {
            Version detected = Version.parse(version());
            if (detected.isBefore(MIN_VERSION))
                throw new IllegalStateException("You need to use a more recent version of Fstorm! >= " + MIN_VERSION);
            /**
             * setStorm3j
             */
            setStorm3j(fstUrl);
            /**
             * setCredentials
             */
            setCredentials(privateKey);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    /**
     * Configure a HTTP client timeout
     * @param timeout (default 0: infinite timeout)
     * @return current Fstorm object with configured timeout
     */
    public Fstorm timeout(int timeout) {
        if(timeout < 0) throw new IllegalArgumentException("timeout must be zero or positive");
        this.timeout = timeout;
        return this;
    }

    public String add(NamedStreamable file) throws IOException, ExecutionException, InterruptedException {


        return add(file, false);
    }

    public String add(NamedStreamable file, boolean wrap) throws IOException, ExecutionException, InterruptedException {
        return add(file, wrap, false);
    }

    public String add(NamedStreamable file, boolean wrap, boolean hashOnly) throws IOException, ExecutionException, InterruptedException {
        return add(Collections.singletonList(file), wrap, hashOnly);
    }

    public String add(List<NamedStreamable> files, boolean wrap, boolean hashOnly) throws IOException, ExecutionException, InterruptedException {
        Multipart m = new Multipart(protocol + "://" + host + ":" + port + version + "add?stream-channels=true&w="+wrap + "&n="+hashOnly, "UTF-8");
        for (NamedStreamable file: files) {
            if (file.isDirectory()) {
                m.addSubtree(Paths.get(""), file);
            } else
                m.addFilePart("file", Paths.get(""), file);
        };
        String res = m.finish();
        List<MerkleNode> collect = JSONParser.parseStream(res).stream()
                .map(x -> MerkleNode.fromJSON((Map<String, Object>) x))
                .collect(Collectors.toList());
        MerkleNode merkleNode = collect.get(0);
        Map<String,Object> json = (Map<String, Object>) merkleNode.toJSON();
        String message = "ipfs add " + json.get("Hash");
        transfer(message);
        return json.get("Hash").toString();

    }

    /**
     * 上链
     * @param message
     * @throws InterruptedException
     * @throws ExecutionException
     */
    private void transfer(String message) throws InterruptedException, ExecutionException, IOException {
        byte[] bytes = message.getBytes();
        String data = Numeric.toHexString(bytes);

        //转账人私钥
        //TODO(添加交易人账户)
//        Credentials credentials = Credentials.create("0xb5a369b10ce738b129d1515d95ed09308d72eba35890bb5b69e953b826275a40");
        // 获取转账人交易数
        FstGetTransactionCount fstGetTransactionCount = storm3j.fstGetTransactionCount(credentials.getAddress(),
                DefaultBlockParameterName.LATEST).sendAsync().get();
        // 获取noce
        BigInteger nonce = fstGetTransactionCount.getTransactionCount();

        nonce = checkNonce(nonce);
        // 创建签名交易
        //TODO(添加交易地址)
        RawTransaction rawTransaction = RawTransaction.createTransaction(nonce, /*DefaultGasProvider.GAS_PRICE*/BigInteger.ZERO,
                DefaultGasProvider.GAS_LIMIT, "0x31d4ce4a57a9a155367176a3bc52c959a08fe420", BigInteger.ZERO,data);
        //签名Transaction，这里要对交易做签名
        byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials);
        String hexValue = Numeric.toHexString(signedMessage);
        //发送交易
        FstSendTransaction fstSendTransaction = storm3j.fstSendRawTransaction(hexValue).sendAsync().get(); }

    private BigInteger checkNonce(BigInteger nonce) throws IOException {
        String path = System.getProperty("user.dir") +"/";
        String configPath=path + credentials.getAddress() + ".txt";
        configPath = URLDecoder.decode(configPath,"utf-8");
        java.io.File file = new java.io.File(configPath);
        boolean exists = file.exists();
        if (exists) {
            String s = FileUtils.readFileByLines(file);
            int length = credentials.getAddress().length();
            BigInteger nonce1 = BigInteger.valueOf(Long.parseLong(s.substring(length)));
            if (nonce1.compareTo(nonce) != -1) {
                nonce = nonce1.add(BigInteger.ONE);
                String content = credentials.getAddress() + nonce.toString();
                FileWriter fileWritter = new FileWriter(file.getName(),false);
                fileWritter.write(content);
                fileWritter.close();
            }else {
                String content = credentials.getAddress() + nonce.toString();
                FileWriter fileWritter = new FileWriter(file.getName(),false);
                fileWritter.write(content);
                fileWritter.close();
            }
        } else {
            String content = credentials.getAddress() + nonce.toString();
            file.createNewFile();
            FileWriter fileWritter = new FileWriter(file.getName(),false);
            fileWritter.write(content);
            fileWritter.close();
        }
        return nonce;
    }

    public List<MerkleNode> ls(Multihash hash) throws IOException {
        Map reply = retrieveMap("ls?arg=" + hash);
        return ((List<Object>) reply.get("Objects"))
                .stream()
                .flatMap(x -> ((List<Object>)((Map) x).get("Links"))
                        .stream()
                        .map(MerkleNode::fromJSON))
                .collect(Collectors.toList());
    }

    public byte[] cat(Multihash hash) throws IOException {
        return retrieve("cat?arg=" + hash);
    }

    public byte[] cat(Multihash hash, String subPath) throws IOException {
        return retrieve("cat?arg=" + hash + URLEncoder.encode(subPath, "UTF-8"));
    }

    public byte[] get(Multihash hash) throws IOException, ExecutionException, InterruptedException {
        String message = "ipfs get " + hash;
        transfer(message);
        return retrieve("get?arg=" + hash);
    }

    public InputStream catStream(Multihash hash) throws IOException {
        return retrieveStream("cat?arg=" + hash);
    }

    public List<Multihash> refs(Multihash hash, boolean recursive) throws IOException {
        String jsonStream = new String(retrieve("refs?arg=" + hash + "&r=" + recursive));
        return JSONParser.parseStream(jsonStream).stream()
                .map(m -> (String) (((Map) m).get("Ref")))
                .map(Cid::decode)
                .collect(Collectors.toList());
    }

    public Map resolve(String scheme, Multihash hash, boolean recursive) throws IOException {
        return retrieveMap("resolve?arg=/" + scheme+"/"+hash +"&r="+recursive);
    }


    public String dns(String domain, boolean recursive) throws IOException {
        Map res = retrieveMap("dns?arg=" + domain + "&r=" + recursive);
        return (String)res.get("Path");
    }

    public Map mount(java.io.File FstormRoot, java.io.File ipnsRoot) throws IOException {
        if (FstormRoot != null && !FstormRoot.exists())
            FstormRoot.mkdirs();
        if (ipnsRoot != null && !ipnsRoot.exists())
            ipnsRoot.mkdirs();
        return (Map)retrieveAndParse("mount?arg=" + (FstormRoot != null ? FstormRoot.getPath() : "/Fstorm" ) + "&arg=" +
                (ipnsRoot != null ? ipnsRoot.getPath() : "/ipns" ));
    }

    // level 2 commands
    public class Refs {
        public List<Multihash> local() throws IOException {
            String jsonStream = new String(retrieve("refs/local"));
            return JSONParser.parseStream(jsonStream).stream()
                    .map(m -> (String) (((Map) m).get("Ref")))
                    .map(Cid::decode)
                    .collect(Collectors.toList());
        }
    }

    /* Pinning an object ensures a local copy of it is kept.
     */
    public class Pin {
        public List<Multihash> add(Multihash hash) throws IOException, ExecutionException, InterruptedException {
            List<Multihash> pins = ((List<Object>) ((Map) retrieveAndParse("pin/add?stream-channels=true&arg=" + hash)).get("Pins"))
                    .stream()
                    .map(x -> Cid.decode((String) x))
                    .collect(Collectors.toList());
            for (Multihash multihash: pins) {
                String message = "ipfs pin add " + multihash;
                transfer(message);
            }
            return pins;
        }

        public Map<Multihash, Object> ls() throws IOException {
            return ls(PinType.direct);
        }

        public Map<Multihash, Object> ls(PinType type) throws IOException {
            return ((Map<String, Object>)(((Map)retrieveAndParse("pin/ls?stream-channels=true&t="+type.name())).get("Keys"))).entrySet()
                    .stream()
                    .collect(Collectors.toMap(x -> Cid.decode(x.getKey()), x-> x.getValue()));
        }

        public List<Multihash> rm(Multihash hash) throws IOException, ExecutionException, InterruptedException {
            return rm(hash, true);
        }

        public List<Multihash> rm(Multihash hash, boolean recursive) throws IOException, ExecutionException, InterruptedException {
            Map json = retrieveMap("pin/rm?stream-channels=true&r=" + recursive + "&arg=" + hash);
            List<Multihash> pins = ((List<Object>) json.get("Pins")).stream().map(x -> Cid.decode((String) x)).collect(Collectors.toList());
            for (Multihash multihash: pins) {
                String message = "ipfs rm " + multihash;
                transfer(message);
            }
            return pins;
        }

        public List<MultiAddress> update(Multihash existing, Multihash modified, boolean unpin) throws IOException {
            return ((List<Object>)((Map)retrieveAndParse("pin/update?stream-channels=true&arg=" + existing + "&arg=" + modified + "&unpin=" + unpin)).get("Pins"))
                    .stream()
                    .map(x -> new MultiAddress((String) x))
                    .collect(Collectors.toList());
        }
    }

    /* 'Fstorm repo' is a plumbing command used to manipulate the repo.
     */
    public class Key {
        public KeyInfo gen(String name, Optional<String> type, Optional<String> size) throws IOException {
            return KeyInfo.fromJson(retrieveAndParse("key/gen?arg=" + name + type.map(t -> "&type=" + t).orElse("") + size.map(s -> "&size=" + s).orElse("")));
        }

        public List<KeyInfo> list() throws IOException {
            return ((List<Object>)((Map)retrieveAndParse("key/list")).get("Keys"))
                    .stream()
                    .map(KeyInfo::fromJson)
                    .collect(Collectors.toList());
        }

        public Object rename(String name, String newName) throws IOException {
            return retrieveAndParse("key/rename?arg="+name + "&arg=" + newName);
        }

        public List<KeyInfo> rm(String name) throws IOException {
            return ((List<Object>)((Map)retrieveAndParse("key/rm?arg=" + name)).get("Keys"))
                    .stream()
                    .map(KeyInfo::fromJson)
                    .collect(Collectors.toList());
        }
    }

    /* 'Fstorm repo' is a plumbing command used to manipulate the repo.
     */
    public class Repo {
        public Object gc() throws IOException {
            return retrieveAndParse("repo/gc");
        }
    }

    public class Pubsub {
        public Object ls() throws IOException {
            return retrieveAndParse("pubsub/ls");
        }

        public Object peers() throws IOException {
            return retrieveAndParse("pubsub/peers");
        }

        public Object peers(String topic) throws IOException {
            return retrieveAndParse("pubsub/peers?arg="+topic);
        }

        /**
         *
         * @param topic
         * @param data url encoded data to be published
         * @return
         * @throws IOException
         */
        public Object pub(String topic, String data) throws Exception {
            return retrieveAndParse("pubsub/pub?arg="+topic + "&arg=" + data);
        }

        public Stream<Map<String, Object>> sub(String topic) throws Exception {
            return sub(topic, ForkJoinPool.commonPool());
        }

        public Stream<Map<String, Object>> sub(String topic, ForkJoinPool threadSupplier) throws Exception {
            return retrieveAndParseStream("pubsub/sub?arg=" + topic, threadSupplier).map(obj -> (Map)obj);
        }

        /**
         * A synchronous method to subscribe which consumes the calling thread
         * @param topic
         * @param results
         * @throws IOException
         */
        public void sub(String topic, Consumer<Map<String, Object>> results, Consumer<IOException> error) throws IOException {
            retrieveAndParseStream("pubsub/sub?arg="+topic, res -> results.accept((Map)res), error);
        }


    }

    /* 'Fstorm block' is a plumbing command used to manipulate raw Fstorm blocks.
     */
    public class Block {
        public byte[] get(Multihash hash) throws IOException {
            return retrieve("block/get?stream-channels=true&arg=" + hash);
        }

        public List<MerkleNode> put(List<byte[]> data) throws IOException {
            return put(data, Optional.empty());
        }

        public List<MerkleNode> put(List<byte[]> data, Optional<String> format) throws IOException {
            // N.B. Once Fstorm implements a bulk put this can become a single multipart call with multiple 'files'
            List<MerkleNode> res = new ArrayList<>();
            for (byte[] value : data) {
                res.add(put(value, format));
            }
            return res;
        }

        public MerkleNode put(byte[] data, Optional<String> format) throws IOException {
            String fmt = format.map(f -> "&format=" + f).orElse("");
            Multipart m = new Multipart(protocol +"://" + host + ":" + port + version+"block/put?stream-channels=true" + fmt, "UTF-8");
            try {
                m.addFilePart("file", Paths.get(""), new NamedStreamable.ByteArrayWrapper(data));
                String res = m.finish();
                return JSONParser.parseStream(res).stream().map(x -> MerkleNode.fromJSON((Map<String, Object>) x)).findFirst().get();
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        public Map stat(Multihash hash) throws IOException {
            return retrieveMap("block/stat?stream-channels=true&arg=" + hash);
        }
    }

    /* 'Fstorm object' is a plumbing command used to manipulate DAG objects directly. {Object} is a subset of {Block}
     */
    public class FstormObject {
        public List<MerkleNode> put(List<byte[]> data) throws IOException {
            Multipart m = new Multipart(protocol +"://" + host + ":" + port + version+"object/put?stream-channels=true", "UTF-8");
            for (byte[] f : data)
                m.addFilePart("file", Paths.get(""), new NamedStreamable.ByteArrayWrapper(f));
            String res = m.finish();
            return JSONParser.parseStream(res).stream().map(x -> MerkleNode.fromJSON((Map<String, Object>) x)).collect(Collectors.toList());
        }

        public List<MerkleNode> put(String encoding, List<byte[]> data) throws IOException {
            if (!"json".equals(encoding) && !"protobuf".equals(encoding))
                throw new IllegalArgumentException("Encoding must be json or protobuf");
            Multipart m = new Multipart(protocol +"://" + host + ":" + port + version+"object/put?stream-channels=true&encoding="+encoding, "UTF-8");
            for (byte[] f : data)
                m.addFilePart("file", Paths.get(""), new NamedStreamable.ByteArrayWrapper(f));
            String res = m.finish();
            return JSONParser.parseStream(res).stream().map(x -> MerkleNode.fromJSON((Map<String, Object>) x)).collect(Collectors.toList());
        }

        public MerkleNode get(Multihash hash) throws IOException {
            Map json = retrieveMap("object/get?stream-channels=true&arg=" + hash);
            json.put("Hash", hash.toBase58());
            return MerkleNode.fromJSON(json);
        }

        public MerkleNode links(Multihash hash) throws IOException {
            Map json = retrieveMap("object/links?stream-channels=true&arg=" + hash);
            return MerkleNode.fromJSON(json);
        }

        public Map<String, Object> stat(Multihash hash) throws IOException {
            return retrieveMap("object/stat?stream-channels=true&arg=" + hash);
        }

        public byte[] data(Multihash hash) throws IOException {
            return retrieve("object/data?stream-channels=true&arg=" + hash);
        }

        public MerkleNode _new(Optional<String> template) throws IOException {
            if (template.isPresent() && !ObjectTemplates.contains(template.get()))
                throw new IllegalStateException("Unrecognised template: "+template.get());
            Map json = retrieveMap("object/new?stream-channels=true"+(template.isPresent() ? "&arg=" + template.get() : ""));
            return MerkleNode.fromJSON(json);
        }

        public MerkleNode patch(Multihash base, String command, Optional<byte[]> data, Optional<String> name, Optional<Multihash> target) throws IOException {
            if (!ObjectPatchTypes.contains(command))
                throw new IllegalStateException("Illegal Object.patch command type: "+command);
            String targetPath = "object/patch/"+command+"?arg=" + base.toBase58();
            if (name.isPresent())
                targetPath += "&arg=" + name.get();
            if (target.isPresent())
                targetPath += "&arg=" + target.get().toBase58();

            switch (command) {
                case "add-link":
                    if (!target.isPresent())
                        throw new IllegalStateException("add-link requires name and target!");
                case "rm-link":
                    if (!name.isPresent())
                        throw new IllegalStateException("link name is required!");
                    return MerkleNode.fromJSON(retrieveMap(targetPath));
                case "set-data":
                case "append-data":
                    if (!data.isPresent())
                        throw new IllegalStateException("set-data requires data!");
                    Multipart m = new Multipart(protocol +"://" + host + ":" + port + version+"object/patch/"+command+"?arg="+base.toBase58()+"&stream-channels=true", "UTF-8");
                    m.addFilePart("file", Paths.get(""), new NamedStreamable.ByteArrayWrapper(data.get()));
                    String res = m.finish();
                    return MerkleNode.fromJSON(JSONParser.parse(res));

                default:
                    throw new IllegalStateException("Unimplemented");
            }
        }
    }

    public class Name {
        public Map publish(Multihash hash) throws IOException {
            return publish(hash, Optional.empty());
        }

        public Map publish(Multihash hash, Optional<String> id) throws IOException {
            return retrieveMap("name/publish?arg=/Fstorm/" + hash + id.map(name -> "&key=" + name).orElse(""));
        }

        public String resolve(Multihash hash) throws IOException {
            Map res = (Map) retrieveAndParse("name/resolve?arg=" + hash);
            return (String)res.get("Path");
        }
    }

    public class DHT {
        public List<Map<String, Object>> findprovs(Multihash hash) throws IOException {
            return getAndParseStream("dht/findprovs?arg=" + hash).stream()
                    .map(x -> (Map<String, Object>) x)
                    .collect(Collectors.toList());
        }

        public Map query(Multihash peerId) throws IOException {
            return retrieveMap("dht/query?arg=" + peerId.toString());
        }

        public Map findpeer(Multihash id) throws IOException {
            return retrieveMap("dht/findpeer?arg=" + id.toString());
        }

        public Map get(Multihash hash) throws IOException {
            return retrieveMap("dht/get?arg=" + hash);
        }

        public Map put(String key, String value) throws IOException {
            return retrieveMap("dht/put?arg=" + key + "&arg="+value);
        }
    }

    public class File {
        public Map ls(Multihash path) throws IOException {
            return retrieveMap("file/ls?arg=" + path);
        }
    }

    // Network commands

    public List<MultiAddress> bootstrap() throws IOException {
        return ((List<String>)retrieveMap("bootstrap/").get("Peers"))
                .stream()
                .flatMap(x -> {
                    try {
                        return Stream.of(new MultiAddress(x));
                    } catch (Exception e) {
                        return Stream.empty();
                    }
                }).collect(Collectors.toList());
    }

    public class Bootstrap {
        public List<MultiAddress> list() throws IOException {
            return bootstrap();
        }

        public List<MultiAddress> add(MultiAddress addr) throws IOException {
            return ((List<String>)retrieveMap("bootstrap/add?arg="+addr).get("Peers")).stream().map(x -> new MultiAddress(x)).collect(Collectors.toList());
        }

        public List<MultiAddress> rm(MultiAddress addr) throws IOException {
            return rm(addr, false);
        }

        public List<MultiAddress> rm(MultiAddress addr, boolean all) throws IOException {
            return ((List<String>)retrieveMap("bootstrap/rm?"+(all ? "all=true&":"")+"arg="+addr).get("Peers")).stream().map(x -> new MultiAddress(x)).collect(Collectors.toList());
        }
    }

    /*  Fstorm swarm is a tool to manipulate the network swarm. The swarm is the
        component that opens, listens for, and maintains connections to other
        Fstorm peers in the internet.
     */
    public class Swarm {
        public List<Peer> peers() throws IOException {
            Map m = retrieveMap("swarm/peers?stream-channels=true");
            return ((List<Object>)m.get("Peers")).stream()
                    .flatMap(json -> {
                        try {
                            return Stream.of(Peer.fromJSON(json));
                        } catch (Exception e) {
                            return Stream.empty();
                        }
                    }).collect(Collectors.toList());
        }

        public Map<Multihash, List<MultiAddress>> addrs() throws IOException {
            Map m = retrieveMap("swarm/addrs?stream-channels=true");
            return ((Map<String, Object>)m.get("Addrs")).entrySet()
                    .stream()
                    .collect(Collectors.toMap(
                            e -> Multihash.fromBase58(e.getKey()),
                            e -> ((List<String>)e.getValue())
                                    .stream()
                                    .map(MultiAddress::new)
                                    .collect(Collectors.toList())));
        }

        public Map connect(MultiAddress multiAddr) throws IOException {
            Map m = retrieveMap("swarm/connect?arg="+multiAddr);
            return m;
        }

        public Map disconnect(MultiAddress multiAddr) throws IOException {
            Map m = retrieveMap("swarm/disconnect?arg="+multiAddr);
            return m;
        }
    }

    public class Dag {
        public byte[] get(Cid cid) throws IOException {
            return retrieve("dag/get?stream-channels=true&arg=" + cid);
        }

        public MerkleNode put(byte[] object) throws IOException {
            return put("json", object, "cbor");
        }

        public MerkleNode put(String inputFormat, byte[] object) throws IOException {
            return put(inputFormat, object, "cbor");
        }

        public MerkleNode put(byte[] object, String outputFormat) throws IOException {
            return put("json", object, outputFormat);
        }

        public MerkleNode put(String inputFormat, byte[] object, String outputFormat) throws IOException {
            String prefix = protocol + "://" + host + ":" + port + version;
            Multipart m = new Multipart(prefix + "dag/put/?stream-channels=true&input-enc=" + inputFormat + "&f=" + outputFormat, "UTF-8");
            m.addFilePart("file", Paths.get(""), new NamedStreamable.ByteArrayWrapper(object));
            String res = m.finish();
            return MerkleNode.fromJSON(JSONParser.parse(res));
        }
    }

    public class Diag {
        public String cmds() throws IOException {
            return new String(retrieve("diag/cmds?stream-channels=true"));
        }

        public String sys() throws IOException {
            return new String(retrieve("diag/sys?stream-channels=true"));
        }
    }

    public Map ping(Multihash target) throws IOException {
        return retrieveMap("ping/" + target.toBase58());
    }

    public Map id(Multihash target) throws IOException {
        return retrieveMap("id/" + target.toBase58());
    }

    public Map id() throws IOException {
        return retrieveMap("id");
    }

    public class Stats {
        public Map bw() throws IOException {
            return retrieveMap("stats/bw");
        }
    }

    // Tools
    public String version() throws IOException {
        Map m = (Map)retrieveAndParse("version");
        return (String)m.get("Version");
    }

    public Map commands() throws IOException {
        return retrieveMap("commands");
    }

    public Map log() throws IOException {
        return retrieveMap("log/tail");
    }

    public class Config {
        public Map show() throws IOException {
            return (Map)retrieveAndParse("config/show");
        }

        public void replace(NamedStreamable file) throws IOException {
            Multipart m = new Multipart(protocol +"://" + host + ":" + port + version+"config/replace?stream-channels=true", "UTF-8");
            m.addFilePart("file", Paths.get(""), file);
            String res = m.finish();
        }

        public Object get(String key) throws IOException {
            Map m = (Map)retrieveAndParse("config?arg="+key);
            return m.get("Value");
        }

        public Map set(String key, Object value) throws IOException {
            return retrieveMap("config?arg=" + key + "&arg=" + value);
        }
    }

    public Object update() throws IOException {
        return retrieveAndParse("update");
    }

    public class Update {
        public Object check() throws IOException {
            return retrieveAndParse("update/check");
        }

        public Object log() throws IOException {
            return retrieveAndParse("update/log");
        }
    }

    private Map retrieveMap(String path) throws IOException {
        return (Map)retrieveAndParse(path);
    }

    private Object retrieveAndParse(String path) throws IOException {
        byte[] res = retrieve(path);
        return JSONParser.parse(new String(res));
    }

    private Stream<Object> retrieveAndParseStream(String path, ForkJoinPool executor) throws IOException {
        BlockingQueue<CompletableFuture<byte[]>> results = new LinkedBlockingQueue<>();
        InputStream in = retrieveStream(path);
        executor.submit(() -> getObjectStream(in,
                res -> {
                    results.add(CompletableFuture.completedFuture(res));
                },
                err -> {
                    CompletableFuture<byte[]> fut = new CompletableFuture<>();
                    fut.completeExceptionally(err);
                    results.add(fut);
                })
        );
        return Stream.generate(() -> {
            try {
                return JSONParser.parse(new String(results.take().get()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * A synchronous stream retriever that consumes the calling thread
     * @param path
     * @param results
     * @throws IOException
     */
    private void retrieveAndParseStream(String path, Consumer<Object> results, Consumer<IOException> err) throws IOException {
        getObjectStream(retrieveStream(path), d -> results.accept(JSONParser.parse(new String(d))), err);
    }

    private byte[] retrieve(String path) throws IOException {
        URL target = new URL(protocol, host, port, version + path);
        return Fstorm.get(target, timeout);
    }

    private static byte[] get(URL target, int timeout) throws IOException {
        HttpURLConnection conn = configureConnection(target, "GET", timeout);

        try {
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();

            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) >= 0)
                resp.write(buf, 0, r);


            return resp.toByteArray();
        } catch (ConnectException e) {
            throw new RuntimeException("Couldn't connect to Fstorm daemon at "+target+"\n Is Fstorm running?");
        } catch (SocketTimeoutException e) {
            throw new RuntimeException(String.format("timeout (%d ms) has been exceeded", timeout));
        } catch (IOException e) {
            String err = Optional.ofNullable(conn.getErrorStream())
                    .map(s->new String(readFully(s)))
                    .orElse(e.getMessage());
            throw new RuntimeException("IOException contacting Fstorm daemon.\nTrailer: " + conn.getHeaderFields().get("Trailer") + " " + err, e);
        }
    }

    private void getObjectStream(InputStream in, Consumer<byte[]> processor, Consumer<IOException> error) {
        byte LINE_FEED = (byte)10;

        try {
            ByteArrayOutputStream resp = new ByteArrayOutputStream();

            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) >= 0) {
                resp.write(buf, 0, r);
                if (buf[r - 1] == LINE_FEED) {
                    processor.accept(resp.toByteArray());
                    resp.reset();
                }
            }
        } catch (IOException e) {
            error.accept(e);
        }
    }

    private List<Object> getAndParseStream(String path) throws IOException {
        InputStream in = retrieveStream(path);
        byte LINE_FEED = (byte)10;

        ByteArrayOutputStream resp = new ByteArrayOutputStream();

        byte[] buf = new byte[4096];
        int r;
        List<Object> res = new ArrayList<>();
        while ((r = in.read(buf)) >= 0) {
            resp.write(buf, 0, r);
            if (buf[r - 1] == LINE_FEED) {
                res.add(JSONParser.parse(new String(resp.toByteArray())));
                resp.reset();
            }
        }
        return res;
    }

    private InputStream retrieveStream(String path) throws IOException {
        URL target = new URL(protocol, host, port, version + path);
        return Fstorm.getStream(target, timeout);
    }

    private static InputStream getStream(URL target, int timeout) throws IOException {
        HttpURLConnection conn = configureConnection(target, "GET", timeout);
        return conn.getInputStream();
    }

    private Map postMap(String path, byte[] body, Map<String, String> headers) throws IOException {
        URL target = new URL(protocol, host, port, version + path);
        return (Map) JSONParser.parse(new String(post(target, body, headers, timeout)));
    }

    private static byte[] post(URL target, byte[] body, Map<String, String> headers, int timeout) throws IOException {
        HttpURLConnection conn = configureConnection(target, "POST", timeout);
        for (String key: headers.keySet())
            conn.setRequestProperty(key, headers.get(key));
        conn.setDoOutput(true);
        OutputStream out = conn.getOutputStream();
        out.write(body);
        out.flush();
        out.close();

        InputStream in = conn.getInputStream();
        return readFully(in);
    }

    private static final byte[] readFully(InputStream in) {
        try {
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r=in.read(buf)) >= 0)
                resp.write(buf, 0, r);
            return resp.toByteArray();
            
        } catch(IOException ex) {
            throw new RuntimeException("Error reading InputStrean", ex);
        }
    }

    private static boolean detectSSL(MultiAddress multiaddress) {
        return multiaddress.toString().contains("/https");
    }
    
    private static HttpURLConnection configureConnection(URL target, String method, int timeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setReadTimeout(timeout);
        return conn;
    }
    @Override
    public void shutdown() {

    }

    @Override
    public Request<?, Storm3ClientVersion> storm3ClientVersion() {
        return null;
    }

    @Override
    public Request<?, Storm3Sha3> storm3Sha3(String s) {
        return null;
    }

    @Override
    public Request<?, NetVersion> netVersion() {
        return null;
    }

    @Override
    public Request<?, NetListening> netListening() {
        return null;
    }

    @Override
    public Request<?, NetPeerCount> netPeerCount() {
        return null;
    }

    @Override
    public Request<?, FstProtocolVersion> fstProtocolVersion() {
        return null;
    }

    @Override
    public Request<?, FstCoinbase> fstCoinbase() {
        return null;
    }

    @Override
    public Request<?, FstSyncing> fstSyncing() {
        return null;
    }

    @Override
    public Request<?, FstMining> fstMining() {
        return null;
    }

    @Override
    public Request<?, FstHashrate> fstHashrate() {
        return null;
    }

    @Override
    public Request<?, FstGasPrice> fstGasPrice() {
        return null;
    }

    @Override
    public Request<?, FstAccounts> fstAccounts() {
        return null;
    }

    @Override
    public Request<?, FstBlockNumber> fstBlockNumber() {
        return null;
    }

    @Override
    public Request<?, FstGetBalance> fstGetBalance(String s, DefaultBlockParameter defaultBlockParameter) {
        return null;
    }

    @Override
    public Request<?, FstGetStorageAt> fstGetStorageAt(String s, BigInteger bigInteger, DefaultBlockParameter defaultBlockParameter) {
        return null;
    }

    @Override
    public Request<?, FstGetTransactionCount> fstGetTransactionCount(String s, DefaultBlockParameter defaultBlockParameter) {
        return null;
    }

    @Override
    public Request<?, FstGetBlockTransactionCountByHash> fstGetBlockTransactionCountByHash(String s) {
        return null;
    }

    @Override
    public Request<?, FstGetBlockTransactionCountByNumber> fstGetBlockTransactionCountByNumber(DefaultBlockParameter defaultBlockParameter) {
        return null;
    }

    @Override
    public Request<?, FstGetUncleCountByBlockHash> fstGetUncleCountByBlockHash(String s) {
        return null;
    }

    @Override
    public Request<?, FstGetUncleCountByBlockNumber> fstGetUncleCountByBlockNumber(DefaultBlockParameter defaultBlockParameter) {
        return null;
    }

    @Override
    public Request<?, FstGetCode> fstGetCode(String s, DefaultBlockParameter defaultBlockParameter) {
        return null;
    }

    @Override
    public Request<?, FstSign> fstSign(String s, String s1) {
        return null;
    }

    @Override
    public Request<?, FstSendTransaction> fstSendTransaction(Transaction transaction) {
        return null;
    }

    @Override
    public Request<?, FstSendTransaction> fstSendRawTransaction(String s) {
        return null;
    }

    @Override
    public Request<?, FstCall> fstCall(Transaction transaction, DefaultBlockParameter defaultBlockParameter) {
        return null;
    }

    @Override
    public Request<?, FstEstimateGas> fstEstimateGas(Transaction transaction) {
        return null;
    }

    @Override
    public Request<?, FstBlock> fstGetBlockByHash(String s, boolean b) {
        return null;
    }

    @Override
    public Request<?, FstBlock> fstGetBlockByNumber(DefaultBlockParameter defaultBlockParameter, boolean b) {
        return null;
    }

    @Override
    public Request<?, FstTransaction> fstGetTransactionByHash(String s) {
        return null;
    }

    @Override
    public Request<?, FstTransaction> fstGetTransactionByBlockHashAndIndex(String s, BigInteger bigInteger) {
        return null;
    }

    @Override
    public Request<?, FstTransaction> fstGetTransactionByBlockNumberAndIndex(DefaultBlockParameter defaultBlockParameter, BigInteger bigInteger) {
        return null;
    }

    @Override
    public Request<?, FstGetTransactionReceipt> fstGetTransactionReceipt(String s) {
        return null;
    }

    @Override
    public Request<?, FstBlock> fstGetUncleByBlockHashAndIndex(String s, BigInteger bigInteger) {
        return null;
    }

    @Override
    public Request<?, FstBlock> fstGetUncleByBlockNumberAndIndex(DefaultBlockParameter defaultBlockParameter, BigInteger bigInteger) {
        return null;
    }

    @Override
    public Request<?, FstGetCompilers> fstGetCompilers() {
        return null;
    }

    @Override
    public Request<?, FstCompileLLL> fstCompileLLL(String s) {
        return null;
    }

    @Override
    public Request<?, FstCompileSolidity> fstCompileSolidity(String s) {
        return null;
    }

    @Override
    public Request<?, FstCompileSerpent> fstCompileSerpent(String s) {
        return null;
    }

    @Override
    public Request<?, FstFilter> fstNewFilter(org.storm3j.protocol.core.methods.request.FstFilter fstFilter) {
        return null;
    }

    @Override
    public Request<?, FstFilter> fstNewBlockFilter() {
        return null;
    }

    @Override
    public Request<?, FstFilter> fstNewPendingTransactionFilter() {
        return null;
    }

    @Override
    public Request<?, FstUninstallFilter> fstUninstallFilter(BigInteger bigInteger) {
        return null;
    }

    @Override
    public Request<?, FstLog> fstGetFilterChanges(BigInteger bigInteger) {
        return null;
    }

    @Override
    public Request<?, FstLog> fstGetFilterLogs(BigInteger bigInteger) {
        return null;
    }

    @Override
    public Request<?, FstLog> fstGetLogs(org.storm3j.protocol.core.methods.request.FstFilter fstFilter) {
        return null;
    }

    @Override
    public Request<?, FstGetWork> fstGetWork() {
        return null;
    }

    @Override
    public Request<?, FstSubmitWork> fstSubmitWork(String s, String s1, String s2) {
        return null;
    }

    @Override
    public Request<?, FstSubmitHashrate> fstSubmitHashrate(String s, String s1) {
        return null;
    }

    @Override
    public Request<?, DbPutString> dbPutString(String s, String s1, String s2) {
        return null;
    }

    @Override
    public Request<?, DbGetString> dbGetString(String s, String s1) {
        return null;
    }

    @Override
    public Request<?, DbPutHex> dbPutHex(String s, String s1, String s2) {
        return null;
    }

    @Override
    public Request<?, DbGetHex> dbGetHex(String s, String s1) {
        return null;
    }

    @Override
    public Request<?, ShhPost> shhPost(org.storm3j.protocol.core.methods.request.ShhPost shhPost) {
        return null;
    }

    @Override
    public Request<?, ShhVersion> shhVersion() {
        return null;
    }

    @Override
    public Request<?, ShhNewIdentity> shhNewIdentity() {
        return null;
    }

    @Override
    public Request<?, ShhHasIdentity> shhHasIdentity(String s) {
        return null;
    }

    @Override
    public Request<?, ShhNewGroup> shhNewGroup() {
        return null;
    }

    @Override
    public Request<?, ShhAddToGroup> shhAddToGroup(String s) {
        return null;
    }

    @Override
    public Request<?, ShhNewFilter> shhNewFilter(ShhFilter shhFilter) {
        return null;
    }

    @Override
    public Request<?, ShhUninstallFilter> shhUninstallFilter(BigInteger bigInteger) {
        return null;
    }

    @Override
    public Request<?, ShhMessages> shhGetFilterChanges(BigInteger bigInteger) {
        return null;
    }

    @Override
    public Request<?, ShhMessages> shhGetMessages(BigInteger bigInteger) {
        return null;
    }

    @Override
    public Flowable<Log> fstLogFlowable(org.storm3j.protocol.core.methods.request.FstFilter fstFilter) {
        return null;
    }

    @Override
    public Flowable<String> fstBlockHashFlowable() {
        return null;
    }

    @Override
    public Flowable<String> fstPendingTransactionHashFlowable() {
        return null;
    }

    @Override
    public Flowable<org.storm3j.protocol.core.methods.response.Transaction> transactionFlowable() {
        return null;
    }

    @Override
    public Flowable<org.storm3j.protocol.core.methods.response.Transaction> pendingTransactionFlowable() {
        return null;
    }

    @Override
    public Flowable<FstBlock> blockFlowable(boolean b) {
        return null;
    }

    @Override
    public Flowable<FstBlock> replayPastBlocksFlowable(DefaultBlockParameter defaultBlockParameter, DefaultBlockParameter defaultBlockParameter1, boolean b) {
        return null;
    }

    @Override
    public Flowable<FstBlock> replayPastBlocksFlowable(DefaultBlockParameter defaultBlockParameter, DefaultBlockParameter defaultBlockParameter1, boolean b, boolean b1) {
        return null;
    }

    @Override
    public Flowable<FstBlock> replayPastBlocksFlowable(DefaultBlockParameter defaultBlockParameter, boolean b, Flowable<FstBlock> flowable) {
        return null;
    }

    @Override
    public Flowable<FstBlock> replayPastBlocksFlowable(DefaultBlockParameter defaultBlockParameter, boolean b) {
        return null;
    }

    @Override
    public Flowable<org.storm3j.protocol.core.methods.response.Transaction> replayPastTransactionsFlowable(DefaultBlockParameter defaultBlockParameter, DefaultBlockParameter defaultBlockParameter1) {
        return null;
    }

    @Override
    public Flowable<org.storm3j.protocol.core.methods.response.Transaction> replayPastTransactionsFlowable(DefaultBlockParameter defaultBlockParameter) {
        return null;
    }

    @Override
    public Flowable<FstBlock> replayPastAndFutureBlocksFlowable(DefaultBlockParameter defaultBlockParameter, boolean b) {
        return null;
    }

    @Override
    public Flowable<org.storm3j.protocol.core.methods.response.Transaction> replayPastAndFutureTransactionsFlowable(DefaultBlockParameter defaultBlockParameter) {
        return null;
    }

    @Override
    public Flowable<NewHeadsNotification> newHeadsNotifications() {
        return null;
    }

    @Override
    public Flowable<LogNotification> logsNotifications(List<String> list, List<String> list1) {
        return null;
    }
}
