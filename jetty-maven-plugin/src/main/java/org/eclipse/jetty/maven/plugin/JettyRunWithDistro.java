//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.codehaus.plexus.component.repository.ComponentDependency;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * JettyRunWithDistro
 *
 * @goal run-distro
 * @requiresDependencyResolution test
 * @execute phase="test-compile"
 * @description Runs unassembled webapp in a locally installed jetty distro
 */
public class JettyRunWithDistro extends JettyRunMojo
{
    
    public static final String JETTY_HOME_GROUPID = "org.eclipse.jetty";
    public static final String JETTY_HOME_ARTIFACTID = "jetty-home";
    
    
    /**
     * @parameter default-value="${plugin}"
     * @readonly
     * @required
     */
    protected PluginDescriptor plugin;
    
    /**
     * The target directory
     * 
     * @parameter default-value="${project.build.directory}"
     * @required
     * @readonly
     */
    protected File target;
    
    
    /**
     * 
     * @parameter
     */
    private File jettyHome;
    
    
    /**
     * 
     * @parameter
     */
    private File jettyBase;
    
    /**
     * Optional list of other modules to
     * activate.
     * @parameter
     */
    private String[] modules;
    
    
    /**
     * Optional list of jetty properties to put on the command line
     * @parameter
     */
    private String[] properties;

    /**
     * @parameter default-value="${session}"
     * @required
     * @readonly
     */
    private MavenSession session;

    /**
     * The project's remote repositories to use for the resolution.
     *
     * @parameter default-value="${project.remoteArtifactRepositories}"
     * @required
     * @readonly
     */
    private List<ArtifactRepository> remoteRepositories;

    /**
     * @component
     */
    private ArtifactResolver artifactResolver;

    
    /**
     * @parameter default-value="${plugin.version}"
     * @readonly
     */
    private String pluginVersion;
    
    
 
    private File targetBase;
    
    private List<Dependency> libExtJars;
    
    
    
    
    // IDEAS:
    // 4. try to make the maven.xml configure a JettyWebAppContext which uses helper classes to configure
    //    itself and apply the context.xml file: that way we can configure the normal jetty deployer
    // 5. try to use the scanner as normal and remake the properties and context.xml file to get the
    //    deployer to automatically redeploy it on changes.

    /** 
     * @see org.eclipse.jetty.maven.plugin.JettyRunMojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        List<Dependency> pdeps = plugin.getPlugin().getDependencies();
        if (pdeps != null && !pdeps.isEmpty())
        {
            boolean warned = false;
            for (Dependency d:pdeps)
            {
                if (d.getGroupId().equalsIgnoreCase("org.eclipse.jetty"))
                {
                    if (!warned)
                    {
                        getLog().warn("Jetty jars detected in <pluginDependencies>: use <modules> in <configuration> parameter instead to select appropriate jetty modules.");
                        warned = true;
                    }
                }
                else
                {
                    if (libExtJars == null)
                        libExtJars = new ArrayList<>();
                    libExtJars.add(d);
                }
            }

        }

        super.execute();
    }


    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#startJetty()
     */
    @Override
    public void startJetty() throws MojoExecutionException
    {
        //don't start jetty locally, set up enough configuration to fork a jetty distro
        try
        {
            printSystemProperties();
            
            //download and install jetty-home if necessary
            configureJettyHome();

            //ensure config of the webapp based on settings in plugin
            configureWebApplication();
            
            //configure jetty.base
            configureJettyBase();
            
            ProcessBuilder command = configureCommand();
            Process process = command.start();
            process.waitFor();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Failed to start Jetty", e);
        }

    }
    

    /**
     * If jetty home does not exist, download it and
     * unpack to build dir.
     * 
     * @throws Exception
     */
    public void configureJettyHome() throws Exception
    {
        if (jettyHome == null)
        {
            //no jetty home, download from repo and unpack it. Get the same version as the plugin
            Artifact jettyHomeArtifact = resolveArtifact(JETTY_HOME_GROUPID, JETTY_HOME_ARTIFACTID, pluginVersion, "zip");      
            JarResource res = (JarResource) JarResource.newJarResource(Resource.newResource(jettyHomeArtifact.getFile()));
            res.copyTo(target);
            //zip will unpack to target/jetty-home-<VERSION>
            jettyHome = new File (target, JETTY_HOME_ARTIFACTID+"-"+pluginVersion);
        }
        else
        {
            if  (!jettyHome.exists())
                throw new IllegalStateException(jettyHome.getAbsolutePath()+" does not exist");
        }
        
        getLog().info("jetty.home = "+jettyHome.getAbsolutePath());
    }


    /**
     * Resolve an Artifact from remote repo if necessary.
     * 
     * @param groupId the groupid of the artifact
     * @param artifactId the artifactId of the artifact
     * @param version the version of the artifact
     * @param extension the extension type of the artifact eg "zip", "jar"
     * @return the artifact from the local or remote repo
     * @throws ArtifactResolverException
     */
    public Artifact resolveArtifact (String groupId, String artifactId, String version, String extension)
    throws ArtifactResolverException
    {
        DefaultArtifactCoordinate coordinate = new DefaultArtifactCoordinate();
        coordinate.setGroupId(groupId);
        coordinate.setArtifactId(artifactId);
        coordinate.setVersion(version);
        coordinate.setExtension(extension);

        ProjectBuildingRequest buildingRequest =
            new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

        buildingRequest.setRemoteRepositories(remoteRepositories);

        return artifactResolver.resolveArtifact( buildingRequest, coordinate ).getArtifact();
    }

    /**
     * Create or configure a jetty base.
     * 
     * @throws Exception
     */
    public void configureJettyBase() throws Exception
    {
        if (jettyBase != null && !jettyBase.exists())
            throw new IllegalStateException(jettyBase.getAbsolutePath() +" does not exist");
        
        targetBase = new File(target, "jetty-base");
        Path targetBasePath = targetBase.toPath();
        Path jettyBasePath = jettyBase.toPath();
        
        if (Files.exists(targetBasePath))
            IO.delete(targetBase);
        
        targetBase.mkdirs();
        
        if (jettyBase != null)
        {
            //copy the existing jetty base, but skip the deployer and the context xml file
            //if there is one
            Files.walkFileTree(jettyBasePath,EnumSet.of(FileVisitOption.FOLLOW_LINKS), 
                               Integer.MAX_VALUE,
                               new SimpleFileVisitor<Path>() 
            {
                /** 
                 * @see java.nio.file.SimpleFileVisitor#preVisitDirectory(java.lang.Object, java.nio.file.attribute.BasicFileAttributes)
                 */
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
                {
                    Path targetDir = targetBasePath.resolve(jettyBasePath.relativize(dir));
                    try
                    {
                        Files.copy(dir, targetDir);
                    }
                    catch (FileAlreadyExistsException e)
                    {
                        if (!Files.isDirectory(targetDir)) //ignore attempt to recreate dir
                                throw e;
                    }
                    return FileVisitResult.CONTINUE;
                }

                /** 
                 * @see java.nio.file.SimpleFileVisitor#visitFile(java.lang.Object, java.nio.file.attribute.BasicFileAttributes)
                 */
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
                {
                    if (contextXml != null && Files.isSameFile(Paths.get(contextXml), file))
                        return FileVisitResult.CONTINUE; //skip copying the context xml file
                    if (file.endsWith("deploy.ini") && file.getParent().endsWith("start.d"))
                        return FileVisitResult.CONTINUE; //skip copying the deployer
                    Files.copy(file, targetBasePath.resolve(jettyBasePath.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }

            });
        }

        //make the jetty base structure
        Path modulesPath = Files.createDirectories(targetBasePath.resolve("modules"));
        Path etcPath = Files.createDirectories(targetBasePath.resolve("etc"));
        Path libPath = Files.createDirectories(targetBasePath.resolve("lib"));
        Path mavenLibPath = Files.createDirectories(libPath.resolve("maven"));

        //copy in the jetty-maven-plugin jar
        URI thisJar = TypeUtil.getLocationOfClass(this.getClass());
        if (thisJar == null)
            throw new IllegalStateException("Can't find jar for jetty-maven-plugin");

        try(InputStream jarStream = thisJar.toURL().openStream();
            FileOutputStream fileStream =  new FileOutputStream(mavenLibPath.resolve("plugin.jar").toFile()))
        {
            IO.copy(jarStream,fileStream);
        }

        //copy in the maven.xml and maven.mod file
        try (InputStream mavenXmlStream = getClass().getClassLoader().getResourceAsStream("maven.xml"); 
             FileOutputStream fileStream = new FileOutputStream(etcPath.resolve("maven.xml").toFile()))
        {
            IO.copy(mavenXmlStream, fileStream);
        }

        try (InputStream mavenModStream = getClass().getClassLoader().getResourceAsStream("maven.mod");
                FileOutputStream fileStream = new FileOutputStream(modulesPath.resolve("maven.mod").toFile()))
        {
            IO.copy(mavenModStream, fileStream);
        }
        
        //if there were plugin dependencies, copy them into lib/ext
        if (libExtJars != null && !libExtJars.isEmpty())
        {
            Path libExtPath = Files.createDirectories(libPath.resolve("ext"));
            for (Dependency d:libExtJars)
            {
                Artifact a = resolveArtifact(d.getGroupId(), d.getArtifactId(), d.getVersion(), d.getType());
                try (InputStream jarStream = new FileInputStream(a.getFile());
                    FileOutputStream fileStream = new FileOutputStream(libExtPath.resolve(d.getGroupId()+"."+d.getArtifactId()+"-"+d.getVersion()+"."+d.getType()).toFile()))
                {
                    IO.copy(jarStream, fileStream);
                }
            }
        }
        
        //create properties file that describes the webapp
        createPropertiesFile(targetBasePath, etcPath);
    }
    
    
    /**
     * Convert webapp config to properties
     * 
     * @param basePath
     * @param etcPath
     * @throws Exception
     */
    public void createPropertiesFile (Path basePath, Path etcPath)
    throws Exception
    {
        File propsFile = Files.createFile(etcPath.resolve("maven.props")).toFile();
        convertWebAppToProperties(propsFile);
    }
    
    
    /**
     * Make the command to spawn a process to
     * run jetty from a distro.
     * 
     * @return
     */
    public ProcessBuilder configureCommand()
    {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-jar");
        cmd.add(new File(jettyHome, "start.jar").getAbsolutePath());
        StringBuilder tmp = new StringBuilder();
        tmp.append("--module=");
        tmp.append("server,http,webapp");
        if (modules != null)
        {
            for (String m:modules)
            {
                if (tmp.indexOf(m) < 0)
                tmp.append(","+m);
            }
        }
        if (libExtJars != null && !libExtJars.isEmpty() && tmp.indexOf("ext") < 0)
            tmp.append(",ext");
        tmp.append(",maven");
         
        cmd.add(tmp.toString());
        
        
        if (properties != null)
        {
            tmp.delete(0, tmp.length());
            for (String p:properties)
                tmp.append(" "+p);
            cmd.add(tmp.toString());
            
        }
        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.directory(targetBase);
        builder.inheritIO();
        
        return builder;
    }
    

    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#startScanner()
     */
    @Override
    public void startScanner() throws Exception
    {
        //don't scan
    }



    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#stopScanner()
     */
    @Override
    public void stopScanner() throws Exception
    {
        //don't scan
    }



    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#restartWebApp(boolean)
     */
    @Override
    public void restartWebApp(boolean reconfigureScanner) throws Exception
    {
        //do nothing
    }

    /** 
     * @see org.eclipse.jetty.maven.plugin.AbstractJettyMojo#configureScanner()
     */
    @Override
    public void configureScanner() throws MojoExecutionException
    {
        //do nothing
    }

}