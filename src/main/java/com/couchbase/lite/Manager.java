package com.couchbase.lite;

import com.couchbase.lite.auth.Authorizer;
import com.couchbase.lite.auth.AuthorizerFactoryManager; // https://github.com/couchbase/couchbase-lite-java-core/issues/41
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.replicator.Puller;
import com.couchbase.lite.replicator.Pusher;
import com.couchbase.lite.replicator.Replication;
import com.couchbase.lite.support.FileDirUtils;
import com.couchbase.lite.support.HttpClientFactory;
import com.couchbase.lite.support.Version;
import com.couchbase.lite.util.Log;
import com.couchbase.lite.util.StreamUtils;

import org.codehaus.jackson.map.ObjectMapper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.Principal; // https://github.com/couchbase/couchbase-lite-java-core/issues/39
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Top-level CouchbaseLite object; manages a collection of databases as a CouchDB server does.
 */
public final class Manager {

    /**
     * @exclude
     */
    public static final String HTTP_ERROR_DOMAIN =  "CBLHTTP";

    /**
     * @exclude
     */
    public static final String DATABASE_SUFFIX_OLD = ".touchdb";

    /**
     * @exclude
     */
    public static final String DATABASE_SUFFIX = ".cblite";

    /**
     * @exclude
     */
    public static final ManagerOptions DEFAULT_OPTIONS = new ManagerOptions();

    /**
     * @exclude
     */
    public static final String LEGAL_CHARACTERS = "[^a-z]{1,}[^a-z0-9_$()/+-]*$";

    public static final String VERSION = Version.VERSION;

    private static final ObjectMapper mapper = new ObjectMapper();
    private ManagerOptions options;
    private File directoryFile;
    private Map<String, Database> databases;
    private List<Replication> replications;
    private ScheduledExecutorService workExecutor;
    private HttpClientFactory defaultHttpClientFactory;
    private Context context;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public static ObjectMapper getObjectMapper() {
        return mapper;
    }

    /**
     * Constructor
     * @throws UnsupportedOperationException - not currently supported
     * @exclude
     */
    @InterfaceAudience.Public
    public Manager() {
        final String detailMessage = "Parameterless constructor is not a valid API call on Android. " +
                " Pure java version coming soon.";
        throw new UnsupportedOperationException(detailMessage);
    }

    /**
     * Enable logging for a particular tag / loglevel combo
     * @param tag Used to identify the source of a log message.  It usually identifies
     *        the class or activity where the log call occurs.
     * @param logLevel The loglevel to enable.  Anything matching this loglevel
     *                 or having a more urgent loglevel will be emitted.  Eg, Log.VERBOSE.
     */
    public static void enableLogging(String tag, int logLevel) {
        Log.enableLogging(tag, logLevel);
    }

    /**
     * Constructor
     *
     * @throws java.lang.SecurityException - Runtime exception that can be thrown by File.mkdirs()
     */
    @InterfaceAudience.Public
    public Manager(Context context, ManagerOptions options) throws IOException {

        Log.i(Database.TAG, "Starting Manager version: %s", Manager.VERSION);

        this.context = context;
        this.directoryFile = context.getFilesDir();
        this.options = (options != null) ? options : DEFAULT_OPTIONS;
        this.databases = new HashMap<String, Database>();
        this.replications = new ArrayList<Replication>();

        directoryFile.mkdirs();
        if (!directoryFile.isDirectory()) {
            throw new IOException(String.format("Unable to create directory for: %s", directoryFile));
        }

        upgradeOldDatabaseFiles(directoryFile);
        workExecutor = Executors.newSingleThreadScheduledExecutor();

    }

    /**
     * Get shared instance
     * @throws UnsupportedOperationException - not currently supported
     * @exclude
     */
    @InterfaceAudience.Public
    public static Manager getSharedInstance() {
        final String detailMessage = "getSharedInstance() is not a valid API call on Android. " +
                " Pure java version coming soon";
        throw new UnsupportedOperationException(detailMessage);
    }

    /**
     * Returns YES if the given name is a valid database name.
     * (Only the characters in "abcdefghijklmnopqrstuvwxyz0123456789_$()+-/" are allowed.)
     */
    @InterfaceAudience.Public
    public static boolean isValidDatabaseName(String databaseName) {
        if (databaseName.length() > 0 && databaseName.length() < 240 &&
                containsOnlyLegalCharacters(databaseName) &&
                Character.isLowerCase(databaseName.charAt(0))) {
            return true;
        }
        return databaseName.equals(Replication.REPLICATOR_DATABASE_NAME);
    }

    /**
     * The root directory of this manager (as specified at initialization time.)
     */
    @InterfaceAudience.Public
    public File getDirectory() {
        return directoryFile;
    }

    /**
     * An array of the names of all existing databases.
     */
    @InterfaceAudience.Public
    public List<String> getAllDatabaseNames() {
        String[] databaseFiles = directoryFile.list(new FilenameFilter() {

            @Override
            public boolean accept(File dir, String filename) {
                if(filename.endsWith(Manager.DATABASE_SUFFIX)) {
                    return true;
                }
                return false;
            }
        });
        List<String> result = new ArrayList<String>();
        for (String databaseFile : databaseFiles) {
            String trimmed = databaseFile.substring(0, databaseFile.length() - Manager.DATABASE_SUFFIX.length());
            String replaced = trimmed.replace(':', '/');
            result.add(replaced);
        }
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    /**
     * Releases all resources used by the Manager instance and closes all its databases.
     */
    @InterfaceAudience.Public
    public void close() {
        Log.i(Database.TAG, "Closing " + this);
        for (Database database : databases.values()) {
            List<Replication> replicators = database.getAllReplications();
            if (replicators != null) {
                for (Replication replicator : replicators) {
                    replicator.stop();
                }
            }
            database.close();
        }
        databases.clear();
        context.getNetworkReachabilityManager().stopListening();
        Log.i(Database.TAG, "Closed " + this);
    }


    /**
     * Returns the database with the given name, or creates it if it doesn't exist.
     * Multiple calls with the same name will return the same Database instance.
     */
    @InterfaceAudience.Public
    public Database getDatabase(String name) throws CouchbaseLiteException {
        boolean mustExist = false;
        Database db = getDatabaseWithoutOpening(name, mustExist);
        if (db != null) {
            boolean opened = db.open();
            if (!opened) {
                return null;
            }
        }
        return db;
    }

    /**
     * Returns the database with the given name, or null if it doesn't exist.
     * Multiple calls with the same name will return the same Database instance.
     */
    @InterfaceAudience.Public
    public Database getExistingDatabase(String name) throws CouchbaseLiteException {
        boolean mustExist = true;
        Database db = getDatabaseWithoutOpening(name, mustExist);
        if (db != null) {
            db.open();
        }
        return db;
    }


    /**
     * Replaces or installs a database from a file.
     *
     * This is primarily used to install a canned database on first launch of an app, in which case
     * you should first check .exists to avoid replacing the database if it exists already. The
     * canned database would have been copied into your app bundle at build time.
     *
     * @param databaseName  The name of the target Database to replace or create.
     * @param databaseStream  InputStream on the source Database file.
     * @param attachmentStreams  Map of the associated source Attachments, or null if there are no attachments.
     *                           The Map key is the name of the attachment, the map value is an InputStream for
     *                           the attachment contents. If you wish to control the order that the attachments
     *                           will be processed, use a LinkedHashMap, SortedMap or similar and the iteration order
     *                           will be honoured.
     **/
    @InterfaceAudience.Public
    public void replaceDatabase(String databaseName, InputStream databaseStream, Map<String,InputStream> attachmentStreams) throws CouchbaseLiteException {
        replaceDatabase(databaseName, databaseStream, attachmentStreams == null ? null : attachmentStreams.entrySet().iterator());
    }

    private void replaceDatabase(String databaseName, InputStream databaseStream, Iterator<Map.Entry<String,InputStream>> attachmentStreams) throws CouchbaseLiteException {
        try {
            Database database = getDatabase(databaseName);
            String dstAttachmentsPath = database.getAttachmentStorePath();
            OutputStream destStream = new FileOutputStream(new File(database.getPath()));
            StreamUtils.copyStream(databaseStream, destStream);
            File attachmentsFile = new File(dstAttachmentsPath);
            FileDirUtils.deleteRecursive(attachmentsFile);
            attachmentsFile.mkdirs();
            if(attachmentStreams != null) {
                StreamUtils.copyStreamsToFolder(attachmentStreams,attachmentsFile);
            }

            database.open();
            database.replaceUUIDs();
        }
        catch (FileNotFoundException e) {
            Log.e(Database.TAG, "", e);
            throw new CouchbaseLiteException(Status.INTERNAL_SERVER_ERROR);
        }
        catch (IOException e) {
            Log.e(Database.TAG, "", e);
            throw new CouchbaseLiteException(Status.INTERNAL_SERVER_ERROR);
        }

    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public HttpClientFactory getDefaultHttpClientFactory() {
        return defaultHttpClientFactory;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void setDefaultHttpClientFactory(
            HttpClientFactory defaultHttpClientFactory) {
        this.defaultHttpClientFactory = defaultHttpClientFactory;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    private static boolean containsOnlyLegalCharacters(String databaseName) {
        Pattern p = Pattern.compile("^[abcdefghijklmnopqrstuvwxyz0123456789_$()+-/]+$");
        Matcher matcher = p.matcher(databaseName);
        return matcher.matches();
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    private void upgradeOldDatabaseFiles(File directory) {
        File[] files = directory.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File file, String name) {
                return name.endsWith(DATABASE_SUFFIX_OLD);
            }
        });

        for (File file : files) {
            String oldFilename = file.getName();
            String newFilename = filenameWithNewExtension(oldFilename, DATABASE_SUFFIX_OLD, DATABASE_SUFFIX);
            File newFile = new File(directory, newFilename);
            if (newFile.exists()) {
                Log.w(Database.TAG, "Cannot rename %s to %s, %s already exists", oldFilename, newFilename, newFilename);
                continue;
            }
            boolean ok = file.renameTo(newFile);
            if (!ok) {
                String msg = String.format("Unable to rename %s to %s", oldFilename, newFilename);
                throw new IllegalStateException(msg);
            }
        }
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    private String filenameWithNewExtension(String oldFilename, String oldExtension, String newExtension) {
        String oldExtensionRegex = String.format("%s$",oldExtension);
        return oldFilename.replaceAll(oldExtensionRegex, newExtension);
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Collection<Database> allOpenDatabases() {
        return databases.values();
    }

    /**
     * Asynchronously dispatches a callback to run on a background thread. The callback will be passed
     * Database instance.  There is not currently a known reason to use it, it may not make
     * sense on the Android API, but it was added for the purpose of having a consistent API with iOS.
     * @exclude
     */
    @InterfaceAudience.Private
    public Future runAsync(String databaseName, final AsyncTask function) throws CouchbaseLiteException {

        final Database database = getDatabase(databaseName);
        return runAsync(new Runnable() {
            @Override
            public void run() {
                function.run(database);
            }
        });

    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    Future runAsync(Runnable runnable) {
        return workExecutor.submit(runnable);
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    private String pathForName(String name) {
        if((name == null) || (name.length() == 0) || Pattern.matches(LEGAL_CHARACTERS, name)) {
            return null;
        }
        name = name.replace('/', '!'); // https://github.com/couchbase/couchbase-lite-java-core/pull/209
        String result = directoryFile.getPath() + File.separator + name + Manager.DATABASE_SUFFIX;
        return result;
    }

    @InterfaceAudience.Private
    /**
     * @exclude
     */
    Replication replicationWithDatabase(Database db, URL remote, boolean push, boolean create, boolean start) {
        for (Replication replicator : replications) {
            if (replicator.getLocalDatabase() == db && replicator.getRemoteUrl().equals(remote) && replicator.isPull() == !push) {
                return replicator;
            }

        }
        if (!create) {
            return null;
        }

        Replication replicator = null;
        final boolean continuous = false;

        if (push) {
            replicator = new Pusher(db, remote, continuous, getWorkExecutor());
        }
        else {
            replicator = new Puller(db, remote, continuous, getWorkExecutor());
        }

        replications.add(replicator);
        if (start) {
            replicator.start();
        }

        return replicator;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public synchronized Database getDatabaseWithoutOpening(String name, boolean mustExist) {
        Database db = databases.get(name);
        if(db == null) {
            if (!isValidDatabaseName(name)) {
                throw new IllegalArgumentException("Invalid database name: " + name);
            }
            if (options.isReadOnly()) {
                mustExist = true;
            }
            String path = pathForName(name);
            if (path == null) {
                return null;
            }
            db = new Database(path, this);
            if (mustExist && !db.exists()) {
                Log.w(Database.TAG, "mustExist is true and db (%s) does not exist", name);
                return null;
            }
            db.setName(name);
            databases.put(name, db);
        }
        return db;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    /* package */ void forgetDatabase(Database db) {

        // remove from cached list of dbs
        databases.remove(db.getName());

        // remove from list of replications
        // TODO: should there be something that actually stops the replication(s) first?
        Iterator<Replication> replicationIterator = this.replications.iterator();
        while (replicationIterator.hasNext()) {
            Replication replication = replicationIterator.next();
            if (replication.getLocalDatabase().getName().equals(db.getName())) {
                replicationIterator.remove();
            }
        }
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Replication getReplicator(Map<String,Object> properties) throws CouchbaseLiteException {
        return getReplicator(properties, null);
    }

    // Adding principal argument is for https://github.com/couchbase/couchbase-lite-java-core/issues/40
    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Replication getReplicator(Map<String,Object> properties, Principal principal) throws CouchbaseLiteException {
        // TODO: in the iOS equivalent of this code, there is: {@"doc_ids", _documentIDs}) - write unit test that detects this bug
        // TODO: ditto for "headers"
        ReplicatorArguments replicatorArguments = new ReplicatorArguments(properties, this, principal); //https://github.com/couchbase/couchbase-lite-java-core/issues/43
        return getReplicator(replicatorArguments);
    }

    // We use this to start replications that need our custom HTTP client to handle mutual SSL auth and Tor
    // This is still all related to https://github.com/couchbase/couchbase-lite-java-core/issues/40
    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Replication getReplicator(ReplicatorArguments replicatorArguments) throws CouchbaseLiteException {
        Authorizer authorizer = null;
        Replication repl = null;
        URL remote = null;

        // The authorizer is set here so that the authorizer can alter values in the arguments, primarily source and target
        // to deal with custom URL schemes, before the rest of the processing occurs.
        // https://github.com/couchbase/couchbase-lite-java-core/issues/41
        AuthorizerFactoryManager authorizerFactoryManager = options.getAuthorizerFactoryManager();
        authorizer = authorizerFactoryManager == null ? null : options.getAuthorizerFactoryManager().findAuthorizer(replicatorArguments);

        // If we have an authorizer that recognizes that the replication manager will be handling the request
        if (!authorizer.isWorkNeeded()) {
            throw new CouchbaseLiteException("no replication needed", new Status(Status.OK));
        }

        Database db;
        String remoteStr;
        // https://github.com/couchbase/couchbase-lite-java-core/issues/43
        if (replicatorArguments.getPush()) {
            db = getExistingDatabase(replicatorArguments.getSource());
            remoteStr = replicatorArguments.getTarget();
        } else {
            remoteStr = replicatorArguments.getSource();
            if(replicatorArguments.getCreateTarget() && !replicatorArguments.getCancel()) {
				boolean mustExist = false;
                db = getDatabaseWithoutOpening(replicatorArguments.getTarget(), mustExist);
                if(!db.open()) {
                    throw new CouchbaseLiteException("cannot open database: " + db, new Status(Status.INTERNAL_SERVER_ERROR));
                }
            } else {
                db = getExistingDatabase(replicatorArguments.getTarget());
            }
            if(db == null) {
                throw new CouchbaseLiteException("database is null", new Status(Status.NOT_FOUND));
            }
        }

        try {
            remote = new URL(remoteStr);
        } catch (MalformedURLException e) {
            throw new CouchbaseLiteException("malformed remote url: " + remoteStr, new Status(Status.BAD_REQUEST));
        }
        if(remote == null) {
            throw new CouchbaseLiteException("remote URL is null: " + remoteStr, new Status(Status.BAD_REQUEST));
        }

        if(!replicatorArguments.getCancel()) {
            // https://github.com/couchbase/couchbase-lite-java-core/issues/41
            HttpClientFactory httpClientFactory = authorizer != null && authorizer.getHttpClientFactory() != null ?
                    authorizer.getHttpClientFactory() : getDefaultHttpClientFactory();
            // https://github.com/couchbase/couchbase-lite-java-core/issues/43
            repl = db.getReplicator(remote, httpClientFactory, replicatorArguments.getPush(), replicatorArguments.getContinuous(), getWorkExecutor());
            if(repl == null) {
                throw new CouchbaseLiteException("unable to create replicator with remote: " + remote, new Status(Status.INTERNAL_SERVER_ERROR));
            }

            if (authorizer != null) {
                repl.setAuthenticator(authorizer);
            }

            // https://github.com/couchbase/couchbase-lite-java-core/issues/40
            if (replicatorArguments.getPrincipal() != null) {
                repl.addPrincipal(replicatorArguments.getPrincipal());
			}

            // https://github.com/couchbase/couchbase-lite-java-core/issues/43
            Map<String, Object> headers = replicatorArguments.getHeaders();
            if (headers != null && !headers.isEmpty()) {
                repl.setHeaders(headers);
            }

            // https://github.com/couchbase/couchbase-lite-java-core/issues/43
            String filterName = replicatorArguments.getFilterName();
            if(filterName != null) {
                repl.setFilter(filterName);
                // https://github.com/couchbase/couchbase-lite-java-core/issues/43
                Map<String,Object> filterParams = replicatorArguments.getQueryParams();
                if(filterParams != null) {
                    repl.setFilterParams(filterParams);
                }
            }

            // https://github.com/couchbase/couchbase-lite-java-core/issues/43
            if(replicatorArguments.getPush()) {
                ((Pusher)repl).setCreateTarget(replicatorArguments.getCreateTarget());
            }
        } else {
            // Cancel replication:
            // https://github.com/couchbase/couchbase-lite-java-core/issues/43
            repl = db.getActiveReplicator(remote, replicatorArguments.getPush());
            if(repl == null) {
                throw new CouchbaseLiteException("unable to lookup replicator with remote: " + remote, new Status(Status.NOT_FOUND));
            }
        }

        return repl;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public ScheduledExecutorService getWorkExecutor() {
        return workExecutor;
    }

    @InterfaceAudience.Private
    public Context getContext() {
        return context;
    }


}

