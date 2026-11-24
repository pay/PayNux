import com.paynux.setup.TarExtractor;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.zip.GZIPInputStream;

/** Usage: ExtractCli <archive.tar.gz> <destDir>. Prints "OK <counts>" or "ERROR <msg>". */
public final class ExtractCli {
    public static void main(String[] a) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(a[0]), 1 << 16))) {
            TarExtractor.Result r = new TarExtractor(new JvmFsOps(), null)
                    .extract(in, new File(a[1]), null);
            System.out.println("OK " + r);
        } catch (Exception e) {
            System.out.println("ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
