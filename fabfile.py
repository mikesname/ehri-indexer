"""
Fabric deployment script for EHRI index helper and Solr config.
"""

from __future__ import with_statement

import os
import datetime
import subprocess
from datetime import datetime

from fabric.api import *
from fabric.contrib.console import confirm
from fabric.contrib.project import upload_project
from contextlib import contextmanager as _contextmanager

# globals
env.prod = False
env.use_ssh_config = True
env.tool_name = 'index-data-converter'
env.service_name = 'solr'
env.tool_jar_path = '/opt/webapps/docview/bin/indexer.jar'
env.solr_core_name = "portal"
env.remote_dir = os.path.join('/opt/webapps/solr6/ehri', env.solr_core_name)
env.config_path = os.path.join(env.remote_dir, "conf")
env.lib_path = os.path.join(env.remote_dir, "lib")
env.data_path = os.path.join(env.remote_dir, "data")
env.user = os.getenv("USER")
env.config_files = ["schema.xml", "solrconfig.xml", "*.txt", "lang/*"]
env.solr_admin_url = "http://localhost:8983/solr/admin"

TIMESTAMP_FORMAT = "%Y%m%d%H%M%S"

# environments
def test():
    "Use the remote testing server"
    env.hosts = ['ehri-test-01']

def stage():
    "Use the remote staging server"
    env.hosts = ['ehri-stage-01']

def prod():
    "Use the remote virtual server"
    env.hosts = ['ehri-portal-01']
    env.prod = True

def deploy():
    """
    Deploy the indexer tool, copy the Solr config, set the permissions
    correctly, and reload the Solr core.
    """
    copy_to_server()
    copy_solr_core()
    _set_permissions()
    reload()

def reload():
    """
    Reload Solr config files by restarting the portal core.
    """
    run("curl \"%(solr_admin_url)s/cores?action=RELOAD&wt=json&core=%(solr_core_name)s\"" % env)

def status():
    """
    Get core status
    """
    run("curl \"%(solr_admin_url)s/cores?action=STATUS&wt=json&core=%(solr_core_name)s\"" % env)

def clean_deploy():
    """Do a clean build, deploy the indexer tool, copy the Solr config, set the permissions
    correctly, and reload the Solr core."""
    local('mvn clean package -DskipTests')
    deploy()

def copy_to_server():
    "Upload the indexer tool to its target directory"
    # Ensure the deployment directory is there...
    local_file = _get_tool_jar_file()
    if not os.path.exists(local_file):
        abort("Jar not found: " + local_file)
    put(local_file, env.tool_jar_path)

def copy_solr_core():
    """Copy the Solr core (lib and conf) to the server"""
    version = _get_artifact_version()
    core_tgz = "solr-config/target/solr-config-%s-solr-core.tar.gz" % version

    temp_name = _get_temp_name(suffix = ".tar.gz")
    remote_name = os.path.join("/tmp", os.path.basename(temp_name))
    put(core_tgz, remote_name)
    run("tar zxvf %s -C %s" % (remote_name, env.remote_dir))
    run("rm %s" % remote_name)
    
def copy_config():
    """Copy the Solr config files to the server"""
    with lcd("solr-config/solr/portal/conf"):
        for f in env.config_files:
            put(f, os.path.join(env.config_path, os.path.dirname(f)))

def start():
    "Start Solr"
    _run_service_cmd("start")

def stop():
    "Stop Solr"
    _run_service_cmd("stop")

def restart():
    "Restart Solr"
    _run_service_cmd("restart")

def _set_permissions():
    """Set the currect permissions on the config files."""
    run("chown -RH %s.webadm %s" % (env.user, env.config_path))
    run("chmod -R g+w %s" % env.config_path)

def _get_tool_jar_file():
    version = _get_artifact_version()
    tool_file = "%s-%s-jar-with-dependencies.jar" % (env.tool_name, version)
    return os.path.join(env.tool_name, "target", tool_file)

def _run_service_cmd(name):
    # NB: This doesn't use sudo() directly because it insists on asking
    # for a password, even though we should have NOPASSWD in visudo.
    with settings(service_task=name):
        run('sudo service %(service_name)s %(service_task)s' % env, pty=False, shell=False)

def _get_artifact_version():
    """Get the current artifact version from Maven"""
    return local(
            "mvn org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate" +
            " -Dexpression=project.version|grep -Ev '(^\[|Download\w+:)'",
            capture=True).strip()

def _get_temp_name(suffix):
    """Get a temporary file name"""
    import tempfile
    tf = tempfile.NamedTemporaryFile(suffix=suffix)
    name = tf.name
    tf.close()
    return name

