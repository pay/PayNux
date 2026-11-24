import com.paynux.setup.FsOps;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** JVM (Linux) FsOps used only by the sandbox tests. */
public final class JvmFsOps implements FsOps {
    public boolean isSymlink(File file) {
        return Files.isSymbolicLink(file.toPath());
    }

    public void symlink(String target, File link) throws IOException {
        Files.createSymbolicLink(link.toPath(), new File(target).toPath());
    }

    public void hardlink(File existing, File link) throws IOException {
        Files.createLink(link.toPath(), existing.toPath());
    }

    public void chmod(File file, int mode) throws IOException {
        Files.setAttribute(file.toPath(), "unix:mode", Integer.valueOf(mode & 07777));
    }
}
