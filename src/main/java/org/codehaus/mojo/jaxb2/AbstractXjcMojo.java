/*
 * Copyright 2005 Jeff Genender.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.mojo.jaxb2;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.StringTokenizer;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.xml.sax.SAXParseException;

import com.sun.tools.xjc.Driver;
import com.sun.tools.xjc.XJCListener;

public abstract class AbstractXjcMojo
    extends AbstractMojo
{

    /**
     * The default maven project object
     * 
     * @parameter expression="${project}"
     * @required
     */
    private MavenProject project;

    /**
     * The optional directory where generated resources can be placed, generated by addons/plugins.
     * 
     * @parameter
     */
    protected File generatedResourcesDirectory;

    /**
     * The package in which the source files will be generated.
     * 
     * @parameter
     */
    protected String packageName;

    /**
     * Catalog file to resolve external entity references support TR9401,
     * XCatalog, and OASIS XML Catalog format.
     * 
     * @parameter
     */
    protected File catalog;

    /**
     * Set HTTP/HTTPS proxy. Format is [user[:password]@]proxyHost[:proxyPort]
     * 
     * @parameter
     */
    protected String httpproxy;

    /**
     * List of files to use for bindings, comma delimited. If none, then all xjb
     * files are used in the bindingDirectory
     * 
     * @parameter
     */
    protected String bindingFiles;

    /**
     * List of files to use for schemas, comma delimited. If none, then all xsd
     * files are used in the schemaDirectory
     * Note: you may use either the 'schemaFiles' or 'schemaListFileName'
     * option (you may use both at once)
     * 
     * @parameter
     */
    protected String schemaFiles;

    /**
     * A filename containing the list of files to use for schemas, comma delimited.
     * If none, then all xsd files are used in the schemaDirectory.
     * Note: you may use either the 'schemaFiles' or 'schemaListFileName'
     * option (you may use both at once)
     * 
     * @parameter
     */
    protected String schemaListFileName;

    /**
     * Treat input schemas as XML DTD (experimental, unsupported).
     * 
     * @parameter default-value="false"
     */
    protected boolean dtd;

    /**
     * Suppress generation of package level annotations (package-info.java)
     * 
     * @parameter default-value="false"
     */
    protected boolean npa;

    /**
     * Do not perform strict validation of the input schema(s)
     * 
     * @parameter default-value="false"
     */
    protected boolean nv;

    /**
     * Treat input schemas as RELAX NG (experimental, unsupported).
     * 
     * @parameter default-value="false"
     */
    protected boolean relaxng;

    /**
     * Treat input as RELAX NG compact syntax (experimental,unsupported)
     * 
     * @parameter default-value="false"
     */
    protected boolean relaxngCompact;

    /**
     * Suppress compiler output
     * 
     * @parameter default-value="false"
     */
    protected boolean quiet;

    /**
     * Generated files will be in read-only mode
     * 
     * @parameter default-value="false"
     */
    protected boolean readOnly;

    /**
     * Be extra verbose
     * 
     * @parameter default-value="false"
     */
    protected boolean verbose;

    /**
     * Treat input as WSDL and compile schemas inside it (experimental,unsupported)
     * 
     * @parameter default-value="false"
     */
    protected boolean wsdl;

    /**
     * Treat input as W3C XML Schema (default)
     * 
     * @parameter default-value="true"
     */
    protected boolean xmlschema;

    /**
     * Allow to use the JAXB Vendor Extensions.
     * 
     * @parameter default-value="false"
     */
    protected boolean extension;

    /**
     * Allow generation of explicit annotations that are needed for JAXB2 to work on RetroTranslator.
     * 
     * @parameter default-value="false"
     */
    protected boolean explicitAnnotation;

    /**
     * Space separated string of extra arguments, for instance <code>-Xfluent-api -episode somefile</code>; These
     * will be passed on to XJC as <code>"-Xfluent-api" "-episode" "somefile"</code> options.
     * 
     * @parameter expression="${xjc.arguments}"
     */
    protected String arguments;

    /**
     * The output path to include in your jar/war/etc if you wish to include your schemas in your artifact.
     * 
     * @parameter
     */
    protected String includeSchemasOutputPath;

    /**
     * Clears the output directory on each run. Defaults to 'true' but if false, will not clear the directory.
     * 
     * @parameter default-value="true"
     */
    protected boolean clearOutputDir;
    
    /**
     * Fails the mojo if no schemas has been found
     * 
     * @parameter default-value="true"
     * @since 1.3
     */    
    protected boolean failOnNoSchemas;

    public AbstractXjcMojo()
    {
        super();
    }

    public void execute()
        throws MojoExecutionException
    {

        try
        {
            if ( isOutputStale() )
            {
                getLog().info( "Generating source..." );

                prepareDirectory( getOutputDirectory() );

                if ( generatedResourcesDirectory != null )
                {
                    prepareDirectory( generatedResourcesDirectory );
                }

                // Need to build a URLClassloader since Maven removed it form
                // the chain
                ClassLoader parent = this.getClass().getClassLoader();
                List classpathFiles = getClasspathElements( project );
                URL[] urls = new URL[classpathFiles.size() + 1];
                StringBuilder classPath = new StringBuilder();
                for ( int i = 0; i < classpathFiles.size(); ++i )
                {
                    getLog().debug( (String) classpathFiles.get( i ) );
                    urls[i] = new File( (String) classpathFiles.get( i ) ).toURL();
                    classPath.append( (String) classpathFiles.get( i ) );
                    classPath.append( File.pathSeparatorChar );
                }

                urls[classpathFiles.size()] = new File( project.getBuild().getOutputDirectory() ).toURL();
                URLClassLoader cl = new URLClassLoader( urls, parent );

                // Set the new classloader
                Thread.currentThread().setContextClassLoader( cl );

                try
                {
                   
                    ArrayList<String> args = getXJCArgs( classPath.toString() );
                    
                    
                    
                    // Run XJC
                    if ( 0 != Driver.run( args.toArray( new String[args.size()] ), new MojoXjcListener() ) )
                    {
                        String msg = "Could not process schema";
                        if ( null != schemaFiles )
                        {
                            URL xsds[] = getXSDFiles();
                            msg += xsds.length > 1 ? "s:" : ":";
                            for ( int i = 0; i < xsds.length; i++ )
                            {
                                msg += "\n  " + xsds[i].getFile();
                            }
                        }
                        else
                        {
                            msg += " files in directory " + getSchemaDirectory();
                        }
                        throw new MojoExecutionException( msg );
                    }

                    touchStaleFile();
                }
                finally
                {
                    // Set back the old classloader
                    Thread.currentThread().setContextClassLoader( parent );
                }

            }
            else
            {
                getLog().info( "No changes detected in schema or binding files, skipping source generation." );
            }

            addCompileSourceRoot( project );

            if ( generatedResourcesDirectory != null )
            {
                Resource resource = new Resource();
                resource.setDirectory( generatedResourcesDirectory.getAbsolutePath() );
                addResource( project, resource );
            }

            if ( includeSchemasOutputPath != null )
            {

                FileUtils.forceMkdir( new File( project.getBuild().getOutputDirectory(), includeSchemasOutputPath ) );

                /**
                 * Resource resource = new Resource();
                 * resource.setDirectory( outputDirectory.getAbsolutePath() );
                 * project.getResources().add( resource );
                 **/
                copyXSDs();
            }
        }
        catch ( NoSchemasException e )
        {
            if ( failOnNoSchemas )
            {
                throw new MojoExecutionException( "no schemas has been found" );
            }
            else
            {
                getLog().warn( "skip xjc execution, no schemas has been found" );
            }
        }
        catch ( MojoExecutionException e )
        {
            throw e;
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }

    }

    protected abstract void addCompileSourceRoot( MavenProject project );

    protected abstract void addResource( MavenProject project, Resource resource );

    protected void copyXSDs()
        throws MojoExecutionException
    {
        URL srcFiles[] = getXSDFiles();

        File baseDir = new File( project.getBuild().getOutputDirectory(), includeSchemasOutputPath );
        for ( int j = 0; j < srcFiles.length; j++ )
        {
            URL from = srcFiles[j];
            // the '/' is the URL-separator
            File to = new File( baseDir, FileUtils.removePath( from.getPath(), '/' ) );
            try
            {
                FileUtils.copyURLToFile( from, to );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( "Error copying file", e );
            }
        }
    }

    private void prepareDirectory( File dir )
        throws MojoExecutionException
    {
        // If the directory exists, whack it to start fresh
        if ( clearOutputDir && dir.exists() )
        {
            try
            {
                FileUtils.deleteDirectory( dir );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( "Error cleaning directory " + dir.getAbsolutePath(), e );
            }
        }

        if ( !dir.exists() )
        {
            if ( !dir.mkdirs() )
            {
                throw new MojoExecutionException( "Could not create directory " + dir.getAbsolutePath() );
            }
        }
    }

    /**
     * @param classPath
     * @return null if no schemas found
     * @throws MojoExecutionException
     */
    private ArrayList<String> getXJCArgs( String classPath )
        throws MojoExecutionException, NoSchemasException
    {
        ArrayList<String> args = new ArrayList<String>();
        if ( npa )
        {
            args.add( "-npa" );
        }
        if ( nv )
        {
            args.add( "-nv" );
        }
        if ( dtd )
        {
            args.add( "-dtd" );
        }
        if ( verbose )
        {
            args.add( "-verbose" );
        }
        if ( quiet )
        {
            args.add( "-quiet" );
        }
        if ( readOnly )
        {
            args.add( "-readOnly" );
        }
        if ( relaxng )
        {
            args.add( "-relaxng" );
        }
        if ( relaxngCompact )
        {
            args.add( "-relaxng-compact" );
        }
        if ( wsdl )
        {
            args.add( "-wsdl" );
        }
        if ( xmlschema )
        {
            args.add( "-xmlschema" );
        }
        if ( explicitAnnotation )
        {
            args.add( "-XexplicitAnnotation" );
        }

        if ( httpproxy != null )
        {
            args.add( "-httpproxy" );
            args.add( httpproxy );
        }

        if ( packageName != null )
        {
            args.add( "-p" );
            args.add( packageName );
        }

        if ( catalog != null )
        {
            args.add( "-catalog" );
            args.add( catalog.getAbsolutePath() );
        }

        if ( extension )
        {
            args.add( "-extension" );
        }

        if ( arguments != null && arguments.trim().length() > 0 )
        {
            for ( String arg : arguments.trim().split( " " ) )
            {
                args.add( arg );
            }
        }

        args.add( "-d" );
        args.add( getOutputDirectory().getAbsolutePath() );
        args.add( "-classpath" );
        args.add( classPath );

        // Bindings
        File bindings[] = getBindingFiles();
        for ( int i = 0; i < bindings.length; i++ )
        {
            args.add( "-b" );
            args.add( bindings[i].getAbsolutePath() );
        }

        List<String> schemas = new ArrayList<String>();

        // XSDs
        if ( schemaFiles != null || schemaListFileName != null )
        {
            URL xsds[] = getXSDFiles();
            for ( int i = 0; i < xsds.length; i++ )
            {
                // args.add( xsds[i].toString() );
                schemas.add( xsds[i].toString() );
            }
        }
        else
        {
            // args.add( getSchemaDirectory().getAbsolutePath() );

            if ( getSchemaDirectory().exists() && getSchemaDirectory().isDirectory() )
            {
                File[] schemaFiles = getSchemaDirectory().listFiles( new XSDFile( getLog() ) );
                if ( schemaFiles != null && schemaFiles.length > 0 )
                {
                    schemas.add( getSchemaDirectory().getAbsolutePath() );
                }
            }
        }

        if ( schemas.isEmpty() )
        {
            throw new NoSchemasException();
        }

        args.addAll( schemas );
        
        getLog().debug( "JAXB 2.0 args: " + args );
                
        return args;
    }
    
    

    /**
     * <code>getSchemasFromFileListing</code> gets all the entries
     * in the given schemaListFileName and adds them to the list
     * of files to send to xjc
     * 
     * @exception MojoExecutionException if an error occurs
     */
    protected void getSchemasFromFileListing( List<URL> files )
        throws MojoExecutionException
    {

        // check that the given file exists
        File schemaListFile = new File( schemaListFileName );

        // create a scanner over the input file
        Scanner scanner = null;
        try
        {
            scanner = new Scanner( schemaListFile ).useDelimiter( "," );
        }
        catch ( FileNotFoundException e )
        {
            throw new MojoExecutionException( "schemaListFileName: " + schemaListFileName
                + " could not be found - error:" + e.getMessage(), e );
        }

        // scan the file and add to the list for processing
        String nextToken = null;
        File nextFile = null;
        while ( scanner.hasNext() )
        {
            nextToken = scanner.next();
            URL url;
            try
            {
                url = new URL( nextToken );
            }
            catch ( MalformedURLException e )
            {
                getLog().debug( nextToken + " doesn't look like a URL..." );
                nextFile = new File( getSchemaDirectory(), nextToken.trim() );
                try
                {
                    url = nextFile.toURI().toURL();
                }
                catch ( MalformedURLException e2 )
                {
                    throw new MojoExecutionException( "Unable to convert file to a URL.", e2 );
                }
            }
            files.add( url );

        }
    }

    /**
     * Returns a file array of xjb files to translate to object models.
     * 
     * @return An array of schema files to be parsed by the schema compiler.
     */
    public final File[] getBindingFiles()
    {

        List<File> bindings = new ArrayList<File>();
        if ( bindingFiles != null )
        {
            for ( StringTokenizer st = new StringTokenizer( bindingFiles, "," ); st.hasMoreTokens(); )
            {
                String schemaName = st.nextToken();
                bindings.add( new File( getBindingDirectory(), schemaName ) );
            }
        }
        else
        {
            getLog().debug( "The binding Directory is " + getBindingDirectory() );
            File[] files = getBindingDirectory().listFiles( new XJBFile() );
            if ( files != null )
            {
                for ( int i = 0; i < files.length; i++ )
                {
                    bindings.add( files[i] );
                }
            }
        }

        return bindings.toArray( new File[] {} );
    }

    /**
     * Returns a file array of xsd files to translate to object models.
     * 
     * @return An array of schema files to be parsed by the schema compiler.
     */
    public final URL[] getXSDFiles()
        throws MojoExecutionException
    {

        // illegal option check
        if ( schemaFiles != null && schemaListFileName != null )
        {

            // make sure user didn't specify both schema input options
            throw new MojoExecutionException(
                                              "schemaFiles and schemaListFileName options were provided, these options may not be used together - schemaFiles: "
                                                  + schemaFiles + " schemaListFileName: " + schemaListFileName );

        }

        List<URL> xsdFiles = new ArrayList<URL>();
        if ( schemaFiles != null )
        {
            for ( StringTokenizer st = new StringTokenizer( schemaFiles, "," ); st.hasMoreTokens(); )
            {
                String schemaName = st.nextToken();
                URL url = null;
                try
                {
                    url = new URL( schemaName );
                }
                catch ( MalformedURLException e )
                {
                    try
                    {
                        url = new File( getSchemaDirectory(), schemaName ).toURI().toURL();
                    }
                    catch ( MalformedURLException e2 )
                    {
                        throw new MojoExecutionException( "Unable to convert file to a URL.", e2 );
                    }
                }
                xsdFiles.add( url );
            }
        }
        else if ( schemaListFileName != null )
        {

            // add all the contents from the schemaListFileName file on disk
            getSchemasFromFileListing( xsdFiles );

        }
        else
        {
            getLog().debug( "The schema Directory is " + getSchemaDirectory() );
            File[] files = getSchemaDirectory().listFiles( new XSDFile( getLog() ) );
            if ( files != null )
            {
                for ( int i = 0; i < files.length; i++ )
                {
                    try
                    {
                        xsdFiles.add( files[i].toURI().toURL() );
                    }
                    catch ( MalformedURLException e )
                    {
                        throw new MojoExecutionException( "Unable to convert file to a URL.", e );
                    }
                }
            }
        }

        return xsdFiles.toArray( new URL[xsdFiles.size()] );
    }

    /**
     * Returns true of any one of the files in the XSD/XJB array are more new than
     * the <code>staleFlag</code> file.
     * 
     * @return True if xsd files have been modified since the last build.
     */
    private boolean isOutputStale()
        throws MojoExecutionException
    {
        URL[] sourceXsds = getXSDFiles();
        File[] sourceXjbs = getBindingFiles();
        boolean stale = !getStaleFile().exists();
        if ( !stale )
        {
            getLog().debug( "Stale flag file exists, comparing to xsds and xjbs." );
            long staleMod = getStaleFile().lastModified();

            for ( int i = 0; i < sourceXsds.length; i++ )
            {
                URLConnection connection;
                try
                {
                    connection = sourceXsds[i].openConnection();
                    connection.connect();
                }
                catch ( IOException e )
                {
                    stale = true;
                    break;
                }

                try
                {
                    if ( connection.getLastModified() > staleMod )
                    {
                        getLog().debug( sourceXsds[i].toString() + " is newer than the stale flag file." );
                        stale = true;
                        break;
                    }
                }
                finally
                {
                    if ( connection instanceof HttpURLConnection )
                    {
                        ( (HttpURLConnection) connection ).disconnect();
                    }
                }
            }

            for ( int i = 0; i < sourceXjbs.length; i++ )
            {
                if ( sourceXjbs[i].lastModified() > staleMod )
                {
                    getLog().debug( sourceXjbs[i].getName() + " is newer than the stale flag file." );
                    stale = true;
                    break;
                }
            }
        }
        return stale;
    }

    private void touchStaleFile()
        throws IOException
    {

        if ( !getStaleFile().exists() )
        {
            getStaleFile().getParentFile().mkdirs();
            getStaleFile().createNewFile();
            getLog().debug( "Stale flag file created." );
        }
        else
        {
            getStaleFile().setLastModified( System.currentTimeMillis() );
        }
    }

    protected abstract File getStaleFile();

    protected abstract File getOutputDirectory();

    protected abstract File getSchemaDirectory();

    protected abstract File getBindingDirectory();

    protected abstract List getClasspathElements( MavenProject project )
        throws DependencyResolutionRequiredException;

    /**
     * A class used to look up .xsd documents from a given directory.
     */
    final class XJBFile
        implements FileFilter
    {

        /**
         * Returns true if the file ends with an xsd extension.
         * 
         * @param file
         *            The filed being reviewed by the filter.
         * @return true if an xsd file.
         */
        public boolean accept( final File file )
        {
            return file.isFile() && file.getName().endsWith( ".xjb" );
        }

    }

    /**
     * A class used to look up .xsd documents from a given directory.
     */
    final class XSDFile
        implements FileFilter
    {

        private Log log;

        public XSDFile( Log log )
        {
            this.log = log;
        }

        /**
         * Returns true if the file ends with an xsd extension.
         * 
         * @param file
         *            The filed being reviewed by the filter.
         * @return true if an xsd file.
         */
        public boolean accept( final java.io.File file )
        {
            boolean accept = file.isFile() && file.getName().endsWith( ".xsd" );
            if ( log.isDebugEnabled() )
            {
                log.debug( "accept " + accept + " for file " + file.getPath() );
            }
            return accept;
        }

    }

    // Class to tap into Maven's logging facility
    class MojoXjcListener
        extends XJCListener
    {

        private String location( SAXParseException e )
        {
            return StringUtils.defaultString( e.getPublicId(), e.getSystemId() ) + "[" + e.getLineNumber() + ","
                + e.getColumnNumber() + "]";
        }

        public void error( SAXParseException arg0 )
        {
            getLog().error( location( arg0 ), arg0 );
        }

        public void fatalError( SAXParseException arg0 )
        {
            getLog().error( location( arg0 ), arg0 );
        }

        public void warning( SAXParseException arg0 )
        {
            getLog().warn( location( arg0 ), arg0 );
        }

        public void info( SAXParseException arg0 )
        {
            getLog().warn( location( arg0 ), arg0 );
        }

        public void message( String arg0 )
        {
            getLog().info( arg0 );
        }

        public void generatedFile( String arg0 )
        {
            getLog().info( arg0 );
        }

    }
}