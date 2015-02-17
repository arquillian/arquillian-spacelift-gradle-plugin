package org.arquillian.spacelift.gradle

import groovy.transform.CompileStatic

import org.arquillian.spacelift.Spacelift
import org.arquillian.spacelift.task.TaskRegistry
import org.arquillian.spacelift.task.archive.UncompressTool;
import org.arquillian.spacelift.task.archive.UntarTool
import org.arquillian.spacelift.task.archive.UnzipTool
import org.arquillian.spacelift.task.net.DownloadTool
import org.gradle.api.Project
import org.gradle.api.internal.GradleDistributionLocator;
import org.slf4j.Logger

@CompileStatic
class DefaultInstallation extends BaseContainerizableObject<DefaultInstallation> implements Installation {

    // version of the product installation belongs to
    DelayedValue<String> version = DelayedValue.of(String.class).from("")

    // product name
    DelayedValue<String> product = DelayedValue.of(String.class).from("")

    // location of installation cache
    DelayedValue<File> fsPath = DelayedValue.of(File.class).from({
        return new File((File) parent['installationsDir'], "${getProduct()}/${getVersion()}/${getFileName()}")
    })

    // url to download from
    DelayedValue<URL> remoteUrl = DelayedValue.of(URL.class)

    // zip file name
    DelayedValue<String> fileName = DelayedValue.of(String.class).from({
        URL url = getRemoteUrl()
        if(url!=null) {
            return guessFileNameFromUrl(url)
        }
        return ""
    })

    // represents directory where installation is extracted to
    DelayedValue<File> home = DelayedValue.of(File.class).from({
        URL url = getRemoteUrl()
        if(url!=null) {
            return new File((File) parent['workspace'], guessDirNameFromUrl(url))
        }
        else {
            return (File) parent['workspace'] //project.spacelift.workspace
        }
    })


    // automatically extract archive
    DelayedValue<Boolean> autoExtract = DelayedValue.of(Boolean.class).from(true)

    // application of additional uncompress tool calls 3during extraction
    DelayedValue<UncompressTool> extractMapper = DelayedValue.of(UncompressTool.class).from({
        return delegate
    })

    DelayedValue<Boolean> isInstalled = DelayedValue.of(Boolean.class).from({
        return getHome().exists()
    })

    // actions to be invoked after installation is done
    DelayedValue<Void> postActions = DelayedValue.of(Void.class)

    // precondition closure returning boolean
    // if true, installation will be installed, if false, skipped
    DelayedValue<Boolean> preconditions = DelayedValue.of(Boolean.class).from(true)

    // tools provided by this installation
    InheritanceAwareContainer<GradleTask, DefaultGradleTask> tools

    DefaultInstallation(String productName, Object parent) {
        super(productName, parent)
        this.product.from(productName)
        this.tools = new InheritanceAwareContainer(this, GradleTask, DefaultGradleTask)
    }

    /**
     * Cloning constructor. Preserves lazy nature of closures to be evaluated later on.
     * @param other Installation to be cloned
     */
    DefaultInstallation(String installationName, DefaultInstallation other) {
        super(installationName, other)
        // use direct access to skip call of getter
        this.version = other.@version.copy()
        this.product = other.@product.copy()
        this.fsPath = other.@fsPath.copy()
        this.remoteUrl = other.@remoteUrl.copy()
        this.fileName = other.@fileName.copy()
        this.home = other.@home.copy()
        this.isInstalled = other.@isInstalled.copy()
        this.autoExtract = other.@autoExtract.copy()
        this.extractMapper = other.@extractMapper.copy()
        this.preconditions = other.@preconditions.copy()
        this.postActions = other.@postActions.copy()
        this.tools =  (InheritanceAwareContainer<GradleTask, DefaultGradleTask>) other.@tools.clone()
    }

    @Override
    public DefaultInstallation clone(String name) {
        new DefaultInstallation(name, this)
    }

    @Override
    File getHome() {
        return home.resolveWith(this)
    }

    @Override
    String getVersion() {
        return version.resolveWith(this)
    }

    @Override
    String getProduct() {
        return product.resolveWith(this)
    }

    @Override
    public boolean isInstalled() {
        return isInstalled.resolveWith(this)
    }

    @Override
    public void registerTools(TaskRegistry registry) {
        // register installed tools
        ((Iterable<GradleTask>)tools).each { GradleTask task ->
            Spacelift.registry().register(task.factory());
        }
    }

    // get installation and perform steps defined in closure after it is extracted
    @Override
    void install(Logger logger) {

        if (!preconditions.resolveWith(this)) {
            // if closure returns false, we did not meet preconditions
            // so we return from installation process
            logger.info(":install:${name} Skipping, did not meet preconditions.")
            return
        }

        File targetFile = getFsPath()
        if(targetFile.exists()) {
            logger.info(":install:${name} Grabbing ${getFileName()} from file system cache")
        }
        else if(getRemoteUrl()!=null){
            // ensure parent directory exists
            targetFile.getParentFile().mkdirs()

            // dowload bits if they do not exists
            logger.info(":install:${name} Grabbing from ${getRemoteUrl()}, storing at ${targetFile}")
            Spacelift.task(DownloadTool).from(getRemoteUrl()).timeout(60000).to(targetFile).execute().await()
        }

        // extract file if set to and at the same time file is defined
        if(getAutoExtract() && getFileName() != "") {
            if(getHome().exists()) {
                logger.info(":install:${name} Deleting previous installation at ${getHome()}")
                // FIXME
                new GradleSpaceliftDelegate().project().getAnt().invokeMethod("delete", [dir: getHome()])
            }

            logger.info(":install:${name} Extracting installation from ${getFileName()}")

            File dest = null
            // based on installation type, we might want to unzip/untar/something else
            switch(getFileName()) {
                case ~/.*jar/:
                    UnzipTool tool = (UnzipTool) Spacelift.task(getFsPath(), UnzipTool).toDir(new File((File) parent['workspace'], getFileName()))
                    dest = extractMapper.apply(tool).execute().await()
                    break
                case ~/.*zip/:
                    UnzipTool tool = (UnzipTool) Spacelift.task(getFsPath(), UnzipTool).toDir((File) parent['workspace'])
                    dest = extractMapper.apply(tool).execute().await()
                    break
                case ~/.*tgz/:
                case ~/.*tar\.gz/:
                    UntarTool tool = (UntarTool) Spacelift.task(getFsPath(), UntarTool).toDir((File) parent['workspace'])
                    dest = extractMapper.apply(tool).execute().await()
                    break
                case ~/.*tbz/:
                case ~/.*tar\.bz2/:
                    UntarTool tool = (UntarTool) Spacelift.task(getFsPath(), UntarTool).bzip2(true).toDir((File) parent['workspace'])
                    dest = extractMapper.apply(tool).execute().await()
                    break
                default:
                    logger.warn(":install:${name} Unable to extract ${getFileName()}, unknown archive type")
            }
            logger.info(":install:${name} was extracted into ${dest}")
        }
        else {
            if(new File(getHome(), getFileName()).exists()) {
                logger.info(":install:${name} Deleting previous installation at ${new File(getHome(), getFileName())}")
                // FIXME
                new GradleSpaceliftDelegate().project().getAnt().invokeMethod("delete", [file: new File(getHome(), getFileName())])

                logger.info(":install:${name} Reusing existing installation ${new File(getHome(), getFileName())}")
            }
            else {
                logger.info(":install:${name} Copying installation to ${(File) parent['workspace']}")
                // FIXME
                new GradleSpaceliftDelegate().project().getAnt().invokeMethod("copy", [file: getFsPath(), tofile: new File(getHome(), getFileName())])
            }
        }

        // register tools
        registerTools(Spacelift.registry())

        // execute post actions
        postActions.resolveWith(this);
    }

    String getFileName() {
        fileName.resolveWith(this)
    }

    boolean getAutoExtract() {
        return autoExtract.resolveWith(this)
    }

    File getFsPath() {
        return fsPath.resolveWith(this)
    }

    URL getRemoteUrl() {
        return remoteUrl.resolveWith(this)
    }

    private String guessFileNameFromUrl(URL url) {
        String path = url.getPath()

        if (path==null || "".equals(path) || path.endsWith("/")) {
            return ""
        }

        File file = new File(url.getPath())
        return file.getName()
    }

    private String guessDirNameFromUrl(URL url) {
        String dirName = guessFileNameFromUrl(url)

        int dot = dirName.lastIndexOf(".")
        if(dot!=-1) {
            int tar = dirName.lastIndexOf(".tar")
            if(tar != -1) {
                return dirName.substring(0, tar)
            }
            else {
                return dirName.substring(0, dot)
            }
        }

        return dirName
    }
}
