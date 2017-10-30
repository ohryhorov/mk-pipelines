/**
 *
 * Wrapper pipeline for automated tests of Openstack components, deployed by MCP.
 * Pipeline stages:
 *  - Deployment of MCP environment with Openstack
 *  - Executing Smoke tests - set of tests to check basic functionality
 *  - Executing component tests - set of tests specific to component being tested,
 *    (or set of all tests).
 *
 * Flow parameters:
 *   EXTRA_REPO                        Repository with additional packages
 *   EXTRA_REPO_PIN                    Pin string for extra repo - eg "origin hostname.local"
 *   EXTRA_REPO_PRIORITY               Repo priority
 *   FAIL_ON_TESTS                     Whether to fail build on tests failures or not
 *   FORMULA_PKG_REVISION              Formulas release to deploy with (stable, testing or nightly)
 *   HEAT_STACK_ZONE                   VM availability zone
 *   OPENSTACK_VERSION                 Version of Openstack being tested
 *   OPENSTACK_API_URL                 OpenStack API address
 *   OPENSTACK_API_CREDENTIALS         Credentials to the OpenStack API
 *   OPENSTACK_API_PROJECT             OpenStack project to connect to
 *   OPENSTACK_API_PROJECT_DOMAIN      OpenStack project domain to connect to
 *   OPENSTACK_API_PROJECT_ID          OpenStack project ID to connect to
 *   OPENSTACK_API_USER_DOMAIN         OpenStack user domain
 *   OPENSTACK_API_CLIENT              Versions of OpenStack python clients
 *   OPENSTACK_API_VERSION             Version of the OpenStack API (2/3)
 *   PROJECT                           Name of project being tested
 *   SALT_OVERRIDES                    Override reclass model parameters
 *   STACK_DELETE                      Whether to cleanup created stack
 *   STACK_TEST_JOB                    Job for launching tests
 *   STACK_TYPE                        Environment type (heat, physical, kvm)
 *   STACK_INSTALL                     Which components of the stack to install
 *   TEST_TEMPEST_TARGET               Salt target for tempest tests
 *   TEST_TEMPEST_PATTERN              Tempest tests pattern
 *   TEST_MILESTONE                    MCP version
 *   TEST_MODEL                        Reclass model of environment
 *   TEST_PASS_THRESHOLD               Persent of passed tests to consider build successful
 *
 **/
common = new com.mirantis.mk.Common()
def artifactoryServer = Artifactory.server('mcp-ci')
def artifactoryUrl = artifactoryServer.getUrl()
def salt_overrides_list = SALT_OVERRIDES.tokenize('\n')
def slave_node = 'python'

def get_test_pattern(project) {
    def pattern_map = ['cinder': 'volume',
                       'barbican': 'barbican_tempest_plugin',
                       'designate': 'designate_tempest_plugin',
                       'glance': 'image',
                       'heat': 'orchestration',
                       'ironic': 'ironic_tempest_plugin',
                       'keystone': 'identity',
                       'nova': 'compute',
                       'neutron': 'network',]
    if (pattern_map.containsKey(project)) {
        return pattern_map[project]
    }
}

if (STACK_TYPE != 'heat' ) {
    slave_node = 'oscore-testing'
}

node(slave_node) {
    def project = PROJECT
    def pkgReviewNameSpace
    def extra_repo = EXTRA_REPO
    def testrail = true
    def test_milestone = ''
    def test_tempest_pattern = TEST_TEMPEST_PATTERN ?: get_test_pattern(project)
    def stack_deploy_job = "deploy-${STACK_TYPE}-${TEST_MODEL}"
    def deployBuild
    def salt_master_url
    def stack_name
    def formula_pkg_revision = 'stable'
    def node_name = slave_node

    try {

        if (common.validInputParam('GERRIT_PROJECT')) {
            project = "${GERRIT_PROJECT}".tokenize('/')[2]
            pkgReviewNameSpace = "binary-dev-local/pkg-review/${GERRIT_CHANGE_NUMBER}"
            //currently artifactory CR repositories  aren't signed - related bug PROD-14585
            extra_repo = "deb [ arch=amd64 trusted=yes ] ${artifactoryUrl}/${pkgReviewNameSpace} /"
            testrail = false
        } else {
            if (common.validInputParam('TEST_MILESTONE')) {
                test_milestone = TEST_MILESTONE
            }
        }

        // Setting extra repo
        if (extra_repo) {
            // by default pin to fqdn of extra repo host
            def extra_repo_pin = EXTRA_REPO_PIN ?: "origin ${extra_repo.tokenize('/')[1]}"
            def extra_repo_priority = EXTRA_REPO_PRIORITY ?: '1200'
            def extra_repo_params = ["linux_system_repo: ${extra_repo}",
                                     "linux_system_repo_priority: ${extra_repo_priority}",
                                     "linux_system_repo_pin: ${extra_repo_pin}",]
            for (item in extra_repo_params) {
               salt_overrides_list.add(item)
            }
        }

        if (common.validInputParam('FORMULA_PKG_REVISION')) {
            formula_pkg_revision = FORMULA_PKG_REVISION
        }

        if (salt_overrides_list) {
            common.infoMsg("Next salt model parameters will be overriden:\n${salt_overrides_list.join('\n')}")
        }

        if (STACK_TYPE == 'kvm') {
            // Deploy KVM environment
            stage('Trigger deploy KVM job') {
                deployBuild = build(job: "deploy-kvm-${TEST_MODEL}", propagate: false, parameters: [
                    [$class: 'BooleanParameterValue', name: 'DEPLOY_OPENSTACK', value: false],
                    [$class: 'BooleanParameterValue', name: 'DESTROY_ENV', value: false],
                    [$class: 'BooleanParameterValue', name: 'CREATE_ENV', value: true],
                    [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: salt_overrides_list.join('\n')],
                ])
            }

            // get salt master url
            salt_master_url = "http://${deployBuild.description.tokenize(' ')[1]}:6969"
            node_name = "${deployBuild.description.tokenize(' ')[2]}"

            // Deploy MCP environment with upstream pipeline
            stage('Trigger deploy MCP job') {
                deployBuild = build(job: "deploy-heat-${TEST_MODEL}", propagate: false, parameters: [
                    [$class: 'StringParameterValue', name: 'SLAVE_NODE', value: node_name],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                    [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
                    [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
                    [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
                    [$class: 'StringParameterValue', name: 'STACK_TYPE', value: 'physical'],
                    [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: salt_master_url],
                    [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                    [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: salt_overrides_list.join('\n')],
                ])
            }
        } else {
            // Deploy MCP environment
            stage('Trigger deploy job') {
                deployBuild = build(job: stack_deploy_job, propagate: false, parameters: [
                    [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                    [$class: 'StringParameterValue', name: 'HEAT_STACK_ZONE', value: HEAT_STACK_ZONE],
                    [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: STACK_INSTALL],
                    [$class: 'StringParameterValue', name: 'STACK_TEST', value: ''],
                    [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                    [$class: 'StringParameterValue', name: 'FORMULA_PKG_REVISION', value: formula_pkg_revision],
                    [$class: 'BooleanParameterValue', name: 'STACK_DELETE', value: false],
                    [$class: 'TextParameterValue', name: 'SALT_OVERRIDES', value: salt_overrides_list.join('\n')],
                ])
            }
            // get salt master url
            salt_master_url = "http://${deployBuild.description.tokenize(' ')[1]}:6969"
            common.infoMsg("Salt API is accessible via ${salt_master_url}")
        }

        // Try to set stack name for stack cleanup job
        if (deployBuild.description) {
            stack_name = deployBuild.description.tokenize(' ')[0]
        }
        if (deployBuild.result != 'SUCCESS'){
            error("Deployment failed, please check ${deployBuild.absoluteUrl}")
        }

        // Perform smoke tests to fail early
        stage('Run Smoke tests') {
            build(job: STACK_TEST_JOB, parameters: [
                [$class: 'StringParameterValue', name: 'SLAVE_NODE', value: node_name],
                [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: salt_master_url],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: TEST_TEMPEST_TARGET],
                [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: 'set=smoke'],
                [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: false],
                [$class: 'StringParameterValue', name: 'PROJECT', value: 'smoke'],
                [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: '100'],
                [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: true],
            ])
        }

        // Perform project specific tests
        if (test_tempest_pattern) {
            stage("Run ${project} tests") {
                build(job: STACK_TEST_JOB, parameters: [
                    [$class: 'StringParameterValue', name: 'SLAVE_NODE', value: node_name],
                    [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: salt_master_url],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_TARGET', value: TEST_TEMPEST_TARGET],
                    [$class: 'StringParameterValue', name: 'TEST_TEMPEST_PATTERN', value: test_tempest_pattern],
                    [$class: 'StringParameterValue', name: 'TEST_MILESTONE', value: test_milestone],
                    [$class: 'StringParameterValue', name: 'TEST_MODEL', value: TEST_MODEL],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_VERSION', value: OPENSTACK_VERSION],
                    [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: testrail.toBoolean()],
                    [$class: 'StringParameterValue', name: 'PROJECT', value: project],
                    [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: TEST_PASS_THRESHOLD],
                    [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: FAIL_ON_TESTS.toBoolean()],
                ])
            }
        }
    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        //
        // Clean
        //
        if (common.validInputParam('STACK_DELETE') && STACK_DELETE.toBoolean() == true) {
            if (stack_name) {
                stage('Trigger cleanup job') {
                    common.errorMsg('Stack cleanup job triggered')
                    build(job: STACK_CLEANUP_JOB, parameters: [
                        [$class: 'StringParameterValue', name: 'STACK_NAME', value: stack_name],
                        [$class: 'StringParameterValue', name: 'SLAVE_NODE', value: node_name],
                        [$class: 'StringParameterValue', name: 'STACK_TYPE', value: STACK_TYPE],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_URL', value: OPENSTACK_API_URL],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_CREDENTIALS', value: OPENSTACK_API_CREDENTIALS],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT', value: OPENSTACK_API_PROJECT],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_DOMAIN', value: OPENSTACK_API_PROJECT_DOMAIN],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_PROJECT_ID', value: OPENSTACK_API_PROJECT_ID],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_USER_DOMAIN', value: OPENSTACK_API_USER_DOMAIN],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_CLIENT', value: OPENSTACK_API_CLIENT],
                        [$class: 'StringParameterValue', name: 'OPENSTACK_API_VERSION', value: OPENSTACK_API_VERSION],
                        [$class: 'BooleanParameterValue', name: 'DESTROY_ENV', value: true],
                    ])
                }
            } else {
                error('Stack name is undefined, cannot cleanup')
            }
        }
    }
}
