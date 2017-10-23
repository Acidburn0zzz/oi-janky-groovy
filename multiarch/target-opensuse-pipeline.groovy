// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

// setup environment variables, etc.
vars.prebuildSetup(this)

env.OPENSUSE_ARCH = vars.imagesMeta[env.ACT_ON_IMAGE]['map'][env.ACT_ON_ARCH]
if (!env.OPENSUSE_ARCH) {
	error("Unknown openSUSE architecture for '${env.ACT_ON_ARCH}'.")
}

node(vars.node(env.ACT_ON_ARCH, env.ACT_ON_IMAGE)) {
	env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker-library/official-images.git',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'oi',
					],
					[
						$class: 'PathRestriction',
						excludedRegions: '',
						includedRegions: 'library/' + env.ACT_ON_IMAGE,
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		dir('opensuse') {
			deleteDir()
			sh '''
				git init --shared
				git config user.name 'Docker Library Bot'
				git config user.email 'github+dockerlibrarybot@infosiftr.com'
				git commit --allow-empty -m 'Initial commit'
			'''
		}
	}

	versions = sh(returnStdout: true, script: '#!/bin/bash -e' + '''
		bashbrew cat -f '{{ range .Entries }}{{ .GitFetch }}{{ "\\n" }}{{ end }}' "$ACT_ON_IMAGE" \
			| awk -F- '$1 == "refs/heads/openSUSE" { print $2 }' \
			| sort -u
	''').trim().tokenize()

	ansiColor('xterm') {
		dir('opensuse') {
			env.VERSIONS = ''
			for (version in versions) {
				dir(version) {
					deleteDir() // make sure we start with a clean slate every time
					withEnv([
						"ROOTFS_URL=http://download.opensuse.org/repositories/Virtualization:/containers:/images:/openSUSE-${version}/images/openSUSE-${version}-docker-guest-docker.${env.OPENSUSE_ARCH}.tar.xz",
						"VERSION=" + version,
					]) {
						stage('Prep ' + version) {
							// use upstream's exact Dockerfile as-is
							sh '''
								curl -fL -o Dockerfile "https://raw.githubusercontent.com/openSUSE/docker-containers-build/openSUSE-$VERSION/$OPENSUSE_ARCH/Dockerfile" \
								|| curl -fL -o Dockerfile "https://raw.githubusercontent.com/openSUSE/docker-containers-build/openSUSE-$VERSION/docker/Dockerfile" \
								|| curl -fL -o Dockerfile "https://raw.githubusercontent.com/openSUSE/docker-containers-build/openSUSE-$VERSION/x86_64/Dockerfile"
							'''
						}
						targetTarball = sh(returnStdout: true, script: '''
							awk 'toupper($1) == "ADD" { print $2 }' Dockerfile
						''').trim() // "openSUSE-Tumbleweed.tar.xz"
						assert targetTarball.endsWith('.tar.xz') // minor sanity check
						stage('Download ' + version) {
							if (0 != sh(returnStatus: true, script: """
								curl -fL -o '${targetTarball}' "\$ROOTFS_URL"
							""")) {
								echo("Failed to download openSUSE rootfs for ${version} on ${env.OPENSUSE_ARCH}; skipping!")
								deleteDir()
							}
						}
						if (fileExists('Dockerfile')) {
							env.VERSIONS += ' ' + version
						}
					}
				}
			}
			env.VERSIONS = env.VERSIONS.trim()

			stage('Commit') {
				sh '''
					git add -A $VERSIONS
					git commit -m "Update for $ACT_ON_ARCH"
					git clean -dfx
					git checkout -- .
				'''
			}
			vars.seedCache(this)

			stage('Generate') {
				sh '''#!/usr/bin/env bash
					set -Eeuo pipefail
					{
						for field in MaintainersString ConstraintsString; do
							val="$(bashbrew cat -f "{{ (first .Entries).$field }}" "$ACT_ON_IMAGE")"
							echo "${field%String}: $val"
						done
						commit="$(git log -1 --format='format:%H')"
						for version in $VERSIONS; do
							echo
							for field in TagsString; do
								val="$(bashbrew cat -f "{{ .TagEntry.$field }}" "$ACT_ON_IMAGE:${version,,}")"
								echo "${field%String}: $val"
							done
							echo "GitRepo: https://doi-janky.infosiftr.net" # obviously bogus
							echo "GitCommit: $commit"
							echo "Directory: $version"
						done
					} > tmp-bashbrew
					set -x
					mv -v tmp-bashbrew "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
					cat "$BASHBREW_LIBRARY/$ACT_ON_IMAGE"
					bashbrew cat "$ACT_ON_IMAGE"
					bashbrew list --uniq --build-order "$ACT_ON_IMAGE"
				'''
			}
		}

		vars.createFakeBashbrew(this)
		vars.bashbrewBuildAndPush(this)

		vars.stashBashbrewBits(this)
	}
}

vars.docsBuildAndPush(this)
