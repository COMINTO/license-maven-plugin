package org.codehaus.mojo.license;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file 
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY 
 * KIND, either express or implied.  See the License for the 
 * specific language governing permissions and limitations 
 * under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.License;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.codehaus.mojo.license.model.DependencyProject;

/**
 * Maven goal for downloading the license files of all the current project's dependencies.
 * 
 * @phase package
 * @goal download-licenses
 * @requiresDependencyResolution test
 * @author Paul Gier
 * @version $Revision$
 */
public class DownloadLicensesMojo
    extends AbstractMojo
{

    /**
     * The Maven Project Object
     * 
     * @parameter default-value="${project}"
     * @readonly
     */
    private MavenProject project;

    /**
     * Used to build a maven projects from artifacts in the remote repository.
     * 
     * @component role="org.apache.maven.project.MavenProjectBuilder"
     * @readonly
     */
    private MavenProjectBuilder projectBuilder;

    /**
     * Location of the local repository.
     * 
     * @parameter default-value="${localRepository}"
     * @readonly
     */
    private org.apache.maven.artifact.repository.ArtifactRepository localRepository;

    /**
     * List of Remote Repositories used by the resolver
     * 
     * @parameter default-value="${project.remoteArtifactRepositories}"
     * @readonly
     */
    private java.util.List remoteRepositories;

    /**
     * Input file containing a mapping between each dependency and it's 
     * license information.
     * 
     * @parameter default-value="${project.basedir}/src/licenses.xml"
     */
    private File licensesSummaryFile;

    /**
     * The directory to which the dependency licenses should be written.
     * 
     * @parameter default-value="${project.build.directory}/licenses"
     */
    private File licensesOutputDirectory;

    /**
     * The output file containing a mapping between each dependency 
     * and it's license information.
     * 
     * @parameter default-value="${project.build.directory}/licenses.xml"
     */
    private File licensesSummaryOutputFile;

    /**
     * Don't show warnings about bad or missing license files.
     * 
     * @parameter default-value="false"
     */
    private boolean quiet;

    /**
     * Include transitive dependencies when downloading license files.
     * 
     * @parameter default-value="true"
     * @since 2.0.0
     */
    private boolean includeTransitiveDependencies;

    /**
     * Main Maven plugin execution
     */
    public void execute()
        throws MojoExecutionException
    {

        if ( !licensesOutputDirectory.exists() )
        {
            licensesOutputDirectory.mkdirs();
        }

        if ( !licensesSummaryOutputFile.getParentFile().exists() )
        {
            licensesSummaryOutputFile.getParentFile().mkdirs();
        }

        // Load license information from previous build so it doesn't have to be located again
        HashMap<String, DependencyProject> configuredDepLicensesMap = new HashMap<String, DependencyProject>();
        if ( licensesSummaryOutputFile.exists() )
        {
            FileInputStream fis = null;
            try
            {
                fis = new FileInputStream( licensesSummaryOutputFile );
                List<DependencyProject> licensesList = LicenseSummaryReader.parseLicenseSummary( fis );
                for ( DependencyProject dep : licensesList )
                {
                    configuredDepLicensesMap.put( dep.getId(), dep );
                }
            }
            catch ( Exception e )
            {
                throw new MojoExecutionException( "Unable to parse license summary file.", e );
            }
            finally
            {
                FileUtil.tryClose( fis );
            }
        }
        else if ( licensesSummaryFile.exists() )
        {
            // Load manually configured license information
            FileInputStream fis = null;
            try
            {
                fis = new FileInputStream( licensesSummaryFile );
                List<DependencyProject> depLicensesList = LicenseSummaryReader.parseLicenseSummary( fis );
                getLog().debug( "Loaded " +  depLicensesList.size() + " licenses" );
                for ( DependencyProject depProject : depLicensesList )
                {
                    getLog().debug( "Downloading licenses for project " + depProject.getId() );
                    this.downloadLicenses( depProject );
                    configuredDepLicensesMap.put( depProject.getId(), depProject );
                }
            }
            catch ( Exception e )
            {
                throw new MojoExecutionException( "Unable to parse license summary file.", e );
            }
            finally
            {
                FileUtil.tryClose( fis );
            }
        }

        // Get the list of build dependencies
        Set<Artifact> depArtifacts = null;

        if ( includeTransitiveDependencies )
        {
            // All project dependencies
            depArtifacts = project.getArtifacts();
        }
        else
        {
            // Only direct project dependencies
            depArtifacts = project.getDependencyArtifacts();
        }

        // The resulting list of licenses after dependency resolution
        List<DependencyProject> depProjectLicenses = new ArrayList<DependencyProject>();

        for ( Artifact artifact : depArtifacts )
        {
            getLog().debug( "Checking licenses for project " + artifact );
            String artifactProjectId = this.getArtifactProjectId( artifact );
            if ( configuredDepLicensesMap.containsKey( artifactProjectId ) )
            {
                DependencyProject depProject = configuredDepLicensesMap.get( artifactProjectId );
                depProject.setVersion( artifact.getVersion() );
                depProjectLicenses.add( depProject );
            }
            else
            {
                DependencyProject depProject = null;
                try
                {
                    depProject = createDependencyProject( artifact );
                    getLog().debug( "Downloading licenses for project " + depProject );
                    this.downloadLicenses( depProject );
                    depProjectLicenses.add( depProject );
                }
                catch ( ProjectBuildingException e )
                {
                    getLog().warn( "Unable to build project: " + artifact );
                    getLog().warn( e );
                }
            }
        }

        try
        {
            LicenseSummaryWriter.writeLicenseSummary( depProjectLicenses, licensesSummaryOutputFile );
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Unable to write license summary file.", e );
        }

    }
    
    /**
     * Returns the project ID for the artifact
     * 
     * @param artifact
     * @return groupId:artifactId
     */
    public String getArtifactProjectId(Artifact artifact)
    {
        return artifact.getGroupId() + ":" + artifact.getArtifactId();
    }

    /**
     * Create a simple DependencyProject object containing the GAV and license info from the MavenProject
     * 
     * @param project
     * @return
     */
    public DependencyProject createDependencyProject( Artifact artifact )
        throws ProjectBuildingException
    {
        MavenProject depMavenProject = null;
        depMavenProject = projectBuilder.buildFromRepository( artifact, remoteRepositories, localRepository );

        DependencyProject dependencyProject =
            new DependencyProject( depMavenProject.getGroupId(), depMavenProject.getArtifactId(), depMavenProject.getVersion() );
        List<License> licenses = depMavenProject.getLicenses();
        for ( License license : licenses )
        {
            dependencyProject.addLicense( license );
        }
        return dependencyProject;
    }

    /**
     * Tries to determine what the name of the downloaded license file should be based on the information in the license
     * object.
     * 
     * @param license
     * @return
     */
    private String getLicenseFileName( License license )
        throws MalformedURLException
    {
        URL licenseUrl = new URL( license.getUrl() );
        File licenseUrlFile = new File( licenseUrl.getPath() );
        String licenseFileName = licenseUrlFile.getName();

        if ( license.getName() != null )
        {
            licenseFileName = license.getName() + " - " + licenseUrlFile.getName();
        }

        // Check if the file has a valid file extention
        final String DEFAULT_EXTENSION = ".txt";
        int extensionIndex = licenseFileName.lastIndexOf( "." );
        if ( extensionIndex == -1 || extensionIndex > ( licenseFileName.length() - 3 ) )
        {
            // This means it isn't a valid file extension, so append the default
            licenseFileName = licenseFileName + DEFAULT_EXTENSION;
        }

        return licenseFileName;
    }

    /**
     * Download the licenses associated with this project
     * 
     * @param depProject The project which generated the dependency
     */
    private void downloadLicenses( DependencyProject depProject )
    {
        List<License> licenses = depProject.getLicenses();

        if ( depProject.getLicenses() == null || depProject.getLicenses().isEmpty() )
        {
            if ( !quiet )
            {
                getLog().warn( "No license information available for: " + depProject );
            }
            return;
        }

        for ( License license : licenses )
        {
            try
            {
                String licenseFileName = this.getLicenseFileName( license );

                File licenseOutputFile = new File( licensesOutputDirectory, licenseFileName );
                if ( licenseOutputFile.exists() )
                {
                    continue;
                }

                LicenseDownloader.downloadLicense( license.getUrl(), licenseOutputFile );

            }
            catch ( MalformedURLException e )
            {
                if ( !quiet )
                {
                    getLog().warn( "POM for dependency " + depProject.toString() + " has an invalid license URL: "
                                       + license.getUrl() );
                }
            }
            catch ( FileNotFoundException e )
            {
                if ( !quiet )
                {
                    getLog().warn( "POM for dependency " + depProject.toString()
                                       + " has a license URL that returns file not found: " + license.getUrl() );
                }
            }
            catch ( IOException e )
            {
                getLog().warn( "Unable to retrieve license for dependency: " + depProject.toString() );
                getLog().warn( license.getUrl() );
                getLog().warn( e.getMessage() );
            }

        }

    }

}
