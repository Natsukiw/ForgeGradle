package net.minecraftforge.gradle.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.patching.ContextualPatch;
import net.minecraftforge.gradle.patching.ContextualPatch.PatchStatus;
import net.minecraftforge.gradle.tasks.abstractutil.EditJarTask;

import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFiles;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.io.Files;

public class ProcessSrcJarTask extends EditJarTask
{
    private List<ResourceHolder> stages  = new LinkedList<ResourceHolder>();

    @Input
    private int                  maxFuzz = 0;

    @Override
    public void doStuffMiddle(Map<String, String> sourceMap, Map<String, byte[]> resourceMap) throws Exception
    {
        ContextProvider provider = new ContextProvider(sourceMap);

        for (ResourceHolder stage : stages)
        {
            if (stage.patchDir != null)
            {
                getLogger().lifecycle("Applying {} patches", stage.name);
                applyPatchStage(stage.name, stage.getPatchFiles(), provider);
            }
            
            if (stage.patchedJar != null)
            {
                getLogger().lifecycle("Exporting {} patched jar", stage.name);
                saveJar(stage.getPatchedJar(), sourceMap, resourceMap);
            }
            
            if (!stage.srcDirs.isEmpty())
            {
                getLogger().lifecycle("Injecting {} files", stage.name);
                for (RelFile rel : stage.getRelInjects())
                {
                    String relative = rel.getRelative();

                    // overwrite duplicates
                    //                    if (sourceMap.containsKey(relative) || resourceMap.containsKey(relative))
                    //                        continue; //ignore duplicates.

                    if (relative.endsWith(".java"))
                    {
                        sourceMap.put(relative, Files.toString(rel.file, Charset.defaultCharset()));
                    }
                    else
                    {
                        resourceMap.put(relative, Files.asByteSource(rel.file).read());
                    }
                }
            }
        }
    }

    public void applyPatchStage(String stage, FileCollection patchFiles, ContextProvider provider) throws Exception
    {
        getLogger().info("Reading patches for stage {}", stage);
        ArrayList<PatchedFile> patches = readPatches(patchFiles, provider);

        boolean fuzzed = false;

        getLogger().info("Applying patches for stage {}", stage);

        Throwable failure = null;

        for (PatchedFile patch : patches)
        {
            List<ContextualPatch.PatchReport> errors = patch.patch.patch(false);
            for (ContextualPatch.PatchReport report : errors)
            {
                // catch failed patches
                if (!report.getStatus().isSuccess())
                {
                    File reject = patch.makeRejectFile();
                    if (reject.exists())
                    {
                        reject.delete();
                    }
                    getLogger().log(LogLevel.ERROR, "Patching failed: {} {}", provider.strip(report.getTarget()), report.getFailure().getMessage());
                    // now spit the hunks
                    int failed = 0;
                    for (ContextualPatch.HunkReport hunk : report.getHunks())
                    {
                        // catch the failed hunks
                        if (!hunk.getStatus().isSuccess())
                        {
                            failed++;
                            getLogger().error("  " + hunk.getHunkID() + ": " + (hunk.getFailure() != null ? hunk.getFailure().getMessage() : "") + " @ " + hunk.getIndex());
                            Files.append(String.format("++++ REJECTED PATCH %d\n", hunk.getHunkID()), reject, Charsets.UTF_8);
                            Files.append(Joiner.on('\n').join(hunk.hunk.lines), reject, Charsets.UTF_8);
                            Files.append(String.format("\n++++ END PATCH\n"), reject, Charsets.UTF_8);
                        }
                        else if (hunk.getStatus() == PatchStatus.Fuzzed)
                        {
                            getLogger().info("  " + hunk.getHunkID() + " fuzzed " + hunk.getFuzz() + "!");
                        }
                    }
                    getLogger().log(LogLevel.ERROR, "  {}/{} failed", failed, report.getHunks().size());
                    getLogger().log(LogLevel.ERROR, "  Rejects written to {}", reject.getAbsolutePath());

                    if (failure == null)
                        failure = report.getFailure();
                }
                // catch fuzzed patches
                else if (report.getStatus() == ContextualPatch.PatchStatus.Fuzzed)
                {
                    getLogger().log(LogLevel.INFO, "Patching fuzzed: {}", provider.strip(report.getTarget()));

                    // set the boolean for later use
                    fuzzed = true;

                    // now spit the hunks
                    for (ContextualPatch.HunkReport hunk : report.getHunks())
                    {
                        // catch the failed hunks
                        if (hunk.getStatus() == PatchStatus.Fuzzed)
                        {
                            getLogger().info("  {} fuzzed {}!", hunk.getHunkID(), hunk.getFuzz());
                        }
                    }

                    if (failure == null)
                        failure = report.getFailure();
                }

                // sucesful patches
                else
                {
                    getLogger().info("Patch succeeded: {}", provider.strip(report.getTarget()));
                }
            }
        }

        if (fuzzed)
        {
            getLogger().lifecycle("Patches Fuzzed!");
        }
    }

    private ArrayList<PatchedFile> readPatches(FileCollection patchFiles, ContextProvider provider) throws IOException
    {
        ArrayList<PatchedFile> patches = new ArrayList<PatchedFile>();

        for (File file : patchFiles.getFiles())
        {
            if (file.getPath().endsWith(".patch"))
            {
                patches.add(readPatch(file, provider));
            }
        }

        return patches;
    }

    private PatchedFile readPatch(File file, ContextProvider provider) throws IOException
    {
        getLogger().debug("Reading patch file: {}", file);
        return new PatchedFile(file, provider);
    }

    @InputFiles
    public FileCollection getAllPatches()
    {
        FileCollection col = null;

        for (ResourceHolder holder : stages)
        {
            if (holder.patchDir == null)
                continue;
            else if (col == null)
                col = holder.getPatchFiles();
            else
                col = getProject().files(col, holder.getPatchFiles());
        }

        return col;
    }

    @InputFiles
    public FileCollection getAllInjects()
    {
        FileCollection col = null;

        for (ResourceHolder holder : stages)
            if (col == null)
                col = holder.getInjects();
            else
                col = getProject().files(col, holder.getInjects());

        return col;
    }

    @Cached
    @OutputFiles
    public FileCollection getAllPatchedJars()
    {
        FileCollection col = null;

        for (ResourceHolder holder : stages)
            if (col == null)
                col = holder.getInjects();
            else
                col = getProject().files(col, holder.getPatchedJar());

        return col;
    }

    public void addStage(String name, Object patchDir, Object outputJar, Object... injects)
    {
        stages.add(new ResourceHolder(name, patchDir, outputJar, Arrays.asList(injects)));
    }

    public void addStage(String name, Object patchDir)
    {
        stages.add(new ResourceHolder(name, patchDir));
    }

    @Override
    public void doStuffAfter() throws Exception
    {
    }

    public int getMaxFuzz()
    {
        return maxFuzz;
    }

    public void setMaxFuzz(int maxFuzz)
    {
        this.maxFuzz = maxFuzz;
    }

    private class PatchedFile
    {
        public final File            fileToPatch;
        public final ContextualPatch patch;

        public PatchedFile(File file, ContextProvider provider) throws IOException
        {
            this.fileToPatch = file;
            this.patch = ContextualPatch.create(Files.toString(file, Charset.defaultCharset()), provider).setAccessC14N(true).setMaxFuzz(getMaxFuzz());
        }

        public File makeRejectFile()
        {
            return new File(fileToPatch.getParentFile(), fileToPatch.getName() + ".rej");
        }
    }

    /**
     * A private inner class to be used with the FmlPatches
     */
    private class ContextProvider implements ContextualPatch.IContextProvider
    {
        private Map<String, String> fileMap;

        private final int           STRIP = 3;

        public ContextProvider(Map<String, String> fileMap)
        {
            this.fileMap = fileMap;
        }

        public String strip(String target)
        {
            target = target.replace('\\', '/');
            int index = 0;
            for (int x = 0; x < STRIP; x++)
            {
                index = target.indexOf('/', index) + 1;
            }
            return target.substring(index);
        }

        @Override
        public List<String> getData(String target)
        {
            target = strip(target);

            if (fileMap.containsKey(target))
            {
                String[] lines = fileMap.get(target).split("\r\n|\r|\n");
                List<String> ret = new ArrayList<String>();
                for (String line : lines)
                {
                    ret.add(line);
                }
                return ret;
            }

            return null;
        }

        @Override
        public void setData(String target, List<String> data)
        {
            target = strip(target);
            fileMap.put(target, Joiner.on(Constants.NEWLINE).join(data));
        }
    }

    /**
     * A little resource holder to make my life a teeny bit easier..
     */
    private final class ResourceHolder
    {
        final String               name;
        private final Object       patchDir;
        private final Object       patchedJar;
        private final List<Object> srcDirs;

        public ResourceHolder(String name, Object patchDir, Object outputJar, List<Object> srcDirs)
        {
            this.name = name;
            this.patchDir = patchDir;
            this.srcDirs = srcDirs;
            this.patchedJar = outputJar;
        }

        public ResourceHolder(String name, Object patchDir)
        {
            this.name = name;
            this.patchDir = patchDir;
            this.srcDirs = Collections.emptyList();
            this.patchedJar = null;
        }

        public FileCollection getPatchFiles()
        {
            File patch = getProject().file(patchDir);
            if (patch.isDirectory())
                return getProject().fileTree(patch);
            else if (patch.getPath().endsWith("zip") || patch.getPath().endsWith("jar"))
                return getProject().zipTree(patch);
            else
                return getProject().files(patch);
        }

        public FileCollection getInjects()
        {
            ArrayList<FileCollection> trees = new ArrayList<FileCollection>(srcDirs.size());
            for (Object o : srcDirs)
                trees.add(getProject().fileTree(o));
            return getProject().files(trees);
        }

        public List<RelFile> getRelInjects()
        {
            LinkedList<RelFile> files = new LinkedList<RelFile>();

            for (Object o : srcDirs)
            {
                File dir = getProject().file(o);
                
                if (!dir.exists())
                    continue;

                if (dir.isDirectory())
                {
                    for (File f : getProject().fileTree(dir))
                    {
                        files.add(new RelFile(f, dir));
                    }
                }
                else
                {
                    files.add(new RelFile(dir, dir.getParentFile()));
                }
            }
            return files;
        }

        public File getPatchedJar()
        {
            if (patchedJar == null)
                return null;
            else
                return getProject().file(patchedJar);
        }
    }

    private static final class RelFile
    {
        public final File file;
        public final File root;

        public RelFile(File file, File root)
        {
            this.file = file;
            this.root = root;
        }

        public String getRelative() throws IOException
        {
            return file.getCanonicalPath().substring(root.getCanonicalPath().length() + 1).replace('\\', '/');
        }
    }

    //@formatter:off
    
    @Override protected boolean storeJarInRam() { return true; }
    @Override public String asRead(String file) { return file; }
    @Override public void doStuffBefore() { }
}
