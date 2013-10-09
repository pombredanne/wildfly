package org.wildfly.extension.undertow.deployment;

import io.undertow.UndertowLogger;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.resource.Resource;
import io.undertow.util.DateUtils;
import io.undertow.util.ETag;
import io.undertow.util.MimeMappings;
import org.jboss.logging.Logger;
import org.xnio.FileAccess;
import org.xnio.IoUtils;
import org.xnio.Pooled;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @author Stuart Douglas
 */
public class VirtualFileResource implements Resource {

    private static final Logger log = Logger.getLogger("io.undertow.server.resources.file");
    private final File file;
    private final String path;
    private final VirtualFileResourceManager manager;

    public VirtualFileResource(final File file, final VirtualFileResourceManager manager, String path) {
        this.file = file;
        this.path = path;
        this.manager = manager;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public Date getLastModified() {
        return new Date(file.lastModified());
    }

    @Override
    public String getLastModifiedString() {
        final Date lastModified = getLastModified();
        if (lastModified == null) {
            return null;
        }
        return DateUtils.toDateString(lastModified);
    }

    @Override
    public ETag getETag() {
        return null;
    }

    @Override
    public String getName() {
        return file.getName();
    }

    @Override
    public boolean isDirectory() {
        return file.isDirectory();
    }

    @Override
    public List<Resource> list() {
        final List<Resource> resources = new ArrayList<Resource>();
        for (String child : file.list()) {
            resources.add(new VirtualFileResource(new File(this.file, child), manager, path));
        }
        return resources;
    }

    @Override
    public String getContentType(final MimeMappings mimeMappings) {
        final String fileName = file.getName();
        int index = fileName.lastIndexOf('.');
        if (index != -1 && index != fileName.length() - 1) {
            return mimeMappings.getMimeType(fileName.substring(index + 1));
        }
        return null;
    }

    @Override
    public void serve(final Sender sender, final HttpServerExchange exchange, final IoCallback callback) {
        abstract class BaseFileTask implements Runnable {
            protected volatile FileChannel fileChannel;

            protected boolean openFile() {
                try {
                    fileChannel = exchange.getConnection().getWorker().getXnio().openFile(file, FileAccess.READ_ONLY);
                } catch (FileNotFoundException e) {
                    exchange.setResponseCode(404);
                    callback.onException(exchange, sender, e);
                    return false;
                } catch (IOException e) {
                    exchange.setResponseCode(500);
                    callback.onException(exchange, sender, e);
                    return false;
                }
                return true;
            }
        }

        class ServerTask extends BaseFileTask implements IoCallback {

            private Pooled<ByteBuffer> pooled;

            @Override
            public void run() {
                if (fileChannel == null) {
                    if (!openFile()) {
                        return;
                    }
                    pooled = exchange.getConnection().getBufferPool().allocate();
                }
                if (pooled != null) {
                    ByteBuffer buffer = pooled.getResource();
                    try {
                        buffer.clear();
                        int res = fileChannel.read(buffer);
                        if (res == -1) {
                            //we are done
                            pooled.free();
                            IoUtils.safeClose(fileChannel);
                            callback.onComplete(exchange, sender);
                            return;
                        }
                        buffer.flip();
                        sender.send(buffer, this);
                    } catch (IOException e) {
                        onException(exchange, sender, e);
                    }
                }

            }

            @Override
            public void onComplete(final HttpServerExchange exchange, final Sender sender) {
                if (exchange.isInIoThread()) {
                    exchange.dispatch(this);
                } else {
                    run();
                }
            }

            @Override
            public void onException(final HttpServerExchange exchange, final Sender sender, final IOException exception) {
                UndertowLogger.REQUEST_IO_LOGGER.ioException(exception);
                if (pooled != null) {
                    pooled.free();
                    pooled = null;
                }
                IoUtils.safeClose(fileChannel);
                if (!exchange.isResponseStarted()) {
                    exchange.setResponseCode(500);
                }
                callback.onException(exchange, sender, exception);
            }
        }

        class TransferTask extends BaseFileTask {
            @Override
            public void run() {
                if (!openFile()) {
                    return;
                }

                sender.transferFrom(fileChannel, new IoCallback() {
                    @Override
                    public void onComplete(HttpServerExchange exchange, Sender sender) {
                        try {
                            IoUtils.safeClose(fileChannel);
                        } finally {
                            callback.onComplete(exchange, sender);
                        }
                    }

                    @Override
                    public void onException(HttpServerExchange exchange, Sender sender, IOException exception) {
                        try {
                            IoUtils.safeClose(fileChannel);
                        } finally {
                            callback.onException(exchange, sender, exception);
                        }
                    }
                });
            }
        }

        BaseFileTask task = manager.getTransferMinSize() > file.length() ? new ServerTask() : new TransferTask();
        if (exchange.isInIoThread()) {
            exchange.dispatch(task);
        } else {
            task.run();
        }
    }

    @Override
    public Long getContentLength() {
        return file.length();
    }

    @Override
    public String getCacheKey() {
        return file.toString();
    }

    @Override
    public File getFile() {
        return file;
    }

    @Override
    public File getResourceManagerRoot() {
        try {
            return manager.getBase().getPhysicalFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public URL getUrl() {
        try {
            return file.toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

}
