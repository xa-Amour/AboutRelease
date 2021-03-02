// -*- mode: groovy -*-
// vim: set filetype=groovy :
import groovy.transform.Field

@Field def commits= [:]
@Field def reposBitbucket = [:]

properties([
    buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '21', numToKeepStr: '100')),
    parameters([
        string(name: 'branch_from', defaultValue: '', description: 'Create branch from this version', trim: true),
        string(name: 'branch_to', defaultValue: '', description: 'Update new branch to this version', trim: true),
        string(name: 'overwrite_branch_to', defaultValue: '', description: 'Over write branch_to if exits', trim: true),
        ]),
    [$class: 'ThrottleJobProperty',
        categories: [],
        limitOneJobWithMatchingParams: false,
        maxConcurrentPerNode: 1,
        maxConcurrentTotal: 0,
        paramsToUseForLimit: '',
        throttleEnabled: false,
        throttleOption: 'project']
    ])

timestamps {
    node('linux') {
        stage('Update release version') {
            releaseVersionUpdate()
            cleanWs()
        }
    }
}

def releaseVersionUpdate() {
    def branchTo = params.branch_to
    def overwriteBranchTo = params.overwrite_branch_to

    def replace = ((branchTo =~ /(\d+.\d+.\d+(.\d)?)/)[0][0]).toString()
    def majorReplace = replace[0].toString()
    def minorReplace = replace[2].toString()
    def hotfixReplace = replace[4].toString()
    def patchReplace = (replace.size() == 7) ? replace[6].toString() : "0"
    def extraReplace = ((branchTo =~ /(_.*)/)[0][0])[1..-1].toString()

    if (overwriteBranchTo) {
        replace = ((overwriteBranchTo =~ /(\d+.\d+.\d+(.\d)(\d)?)/)[0][0]).toString()
        majorReplace = replace[0].toString()
        minorReplace = replace[2].toString()
        hotfixReplace = replace[4].toString()
        patchReplace = replace.split('\\.')[-1].toString()
        extraReplace = ((overwriteBranchTo =~ /(_.*)/)[0][0])[1..-1].toString()
    }

    def repoMap = [
            "sdk": ['xxx_engine2', 'xxx_player', 'xxx_sdk_script', 'xxx_sdk', 'xxx_sdk_extensions', 'xxx_sdk_private', 'xxx_streaming_kit'],
            "apps": ['xxx-windows', 'xxx-android', 'xxx-ios', 'xxx2m-windows', 'xxx2m-android', 'xxx2m-ios'],
        ]

    repoMap.each { key, value ->
        value.each { repo ->
            reposBitbucket.put("./${repo}", [
                "ssh://git@git.xxx/${key}/${repo}.git",
                params.branch_from ?: "baseBranch",
                "$HOME/github/${repo}.git",
                "sshagent_Placeholder"]
            )
        }
    }

    sh '''
        git config --global core.compression 9
        git config --global core.packedGitLimit 512m
        git config --global core.packedGitWindowSize 512m
        git config --global pack.deltaCacheSize 2047m
        git config --global pack.packSizeLimit 2047m
        git config --global pack.windowMemory 2047m
        git config --global http.postBuffer 524288000

        if [ -d "$HOME/github" ]; then
            umount $HOME/github -f || true
        else
            mkdir $HOME/github || true
        fi
        mount.cifs //serverIP_Placeholder/github/ $HOME/github -o user=guest,pass="" || true

        umount /root/ramdisk || true
        if [ ! -d "/tmp/ramdisk" ]; then
            mkdir /tmp/ramdisk || true
        else
            umount /tmp/ramdisk || true
        fi
        mount -t tmpfs -o size=8196m ramdisk /tmp/ramdisk
        timedatectl set-timezone Asia/Shanghai

        rm -rf ${WORKSPACE}/*
    '''

    retry(5) {
        reposBitbucket.each{ k, v -> checkout(k, v)}
    }

    sshagent(['sshagent_Placeholder']) {
        withEnv([
            "replace=${replace}",
            "majorReplace=${majorReplace}",
            "minorReplace=${minorReplace}",
            "hotfixReplace=${hotfixReplace}",
            "patchReplace=${patchReplace}",
            "extraReplace=${extraReplace}",
            "branchTo=${branchTo}"]) {
            reposBitbucket.each { k, v ->
                def repo_name = k.split('/')[-1]
                withEnv(["repoName=${repo_name}",]) {
                    sh '''
                        GIT_SSH_COMMAND="$GIT_SSH_COMMAND -oStrictHostKeyChecking=no"

                        if [ "`git branch -r|grep origin/${branchTo}|wc -l`" != "0" ]; then
                            echo "This branch already exists"
                            exit 1
                        fi

                        if [ "$repoName" == "xxx_sdk" ]; then
                            echo "*****Push ${repoName} to Bitbucket"
                            pushd "${repoName}"
                            git checkout -b ${branchTo}

                            echo "*****Modify version info of xxx_version.h*****"
                            s_extraReplace="\\"${extraReplace}\\""
                            s_replace="\\"${replace}\\""
                            sed -i -e "s|#define COMPANY_XXX_VERSION_MAJOR .*|#define COMPANY_XXX_VERSION_MAJOR ${majorReplace}|g" ./src/main/xxx_version.h
                            sed -i -e "s|#define COMPANY_XXX_VERSION_MINOR .*|#define COMPANY_XXX_VERSION_MINOR ${minorReplace}|g" ./src/main/xxx_version.h
                            sed -i -e "s|#define COMPANY_XXX_VERSION_HOTFIX .*|#define COMPANY_XXX_VERSION_HOTFIX ${hotfixReplace}|g" ./src/main/xxx_version.h
                            sed -i -e "s|#define COMPANY_XXX_VERSION_PATCH .*|#define COMPANY_XXX_VERSION_PATCH ${patchReplace}|g" ./src/main/xxx_version.h
                            sed -i -e "s|#define COMPANY_XXX_VERSION_EXTRA .*|#define COMPANY_XXX_VERSION_EXTRA ${s_extraReplace}|g" ./src/main/xxx_version.h
                            sed -i '/Version number of package/{n;d}' ./src/main/xxx_version.h
                            sed -i "/Version number of package/a\\#define COMPANY_XXX_VERSION ${s_replace}" ./src/main/xxx_version.h

                            echo "*****Modify version info of companysdk.rc*****"
                            s_fileVersionReplace="\\"${majorReplace}.${minorReplace}.${hotfixReplace}.${patchReplace}\\""
                            s_ProductVersionReplace="\\"${majorReplace}.${minorReplace}.${hotfixReplace}.${patchReplace}\\""
                            sed -i -e "s| FILEVERSION .*| FILEVERSION ${majorReplace},${minorReplace},${hotfixReplace},${patchReplace}|g" ./src/sys/win32/companysdk.rc
                            sed -i -e "s| PRODUCTVERSION .*| PRODUCTVERSION ${majorReplace},${minorReplace},${hotfixReplace},${patchReplace}|g" ./src/sys/win32/companysdk.rc
                            sed -i -e "s|            VALUE "\\"FileVersion\\"", .*|            VALUE "\\"FileVersion\\"", ${s_fileVersionReplace}|g" ./src/sys/win32/companysdk.rc
                            sed -i -e "s|            VALUE "\\"ProductVersion\\"", .*|            VALUE "\\"ProductVersion\\"", ${s_ProductVersionReplace}|g" ./src/sys/win32/companysdk.rc

                            echo "*****Modify version info of proj.ios/CompanyRtcCryptoLoader/Info.plist*****"
                            sed -i '/CFBundleShortVersionString/{n;d}' ./proj.ios/CompanyRtcCryptoLoader/Info.plist
                            sed -i "/CFBundleShortVersionString/a\\	<string>${replace}</string>" ./proj.ios/CompanyRtcCryptoLoader/Info.plist

                            echo "*****Modify version info of proj.ios/CompanyRtcKit/Info.plist*****"
                            sed -i '/CFBundleShortVersionString/{n;d}' ./proj.ios/CompanyRtcKit/Info.plist
                            sed -i "/CFBundleShortVersionString/a\\	<string>${replace}</string>" ./proj.ios/CompanyRtcKit/Info.plist

                            echo "*****Modify version info of proj.mac/Info.plist*****"
                            sed -i '/CFBundleShortVersionString/{n;d}' ./proj.mac/Info.plist
                            sed -i "/CFBundleShortVersionString/a\\	<string>${replace}</string>" ./proj.mac/Info.plist

                            echo "*****Add version info of rte_version*****"
                            rm -rf ./rte_version && touch rte_version
                            echo ${replace} > rte_version

                            git add . && git commit -m "Upgrade version to ${branchTo}" && git push origin ${branchTo}

                            cat ./src/main/xxx_version.h
                            cat ./src/sys/win32/companysdk.rc
                            cat ./proj.ios/CompanyRtcCryptoLoader/Info.plist
                            cat ./proj.ios/CompanyRtcKit/Info.plist
                            cat ./proj.mac/Info.plist
                            popd
                        else
                            echo "*****Push ${repoName} to Bitbucket"
                            pushd "${repoName}"
                            git checkout -b ${branchTo}
                            git push origin ${branchTo}
                            popd
                        fi
                    '''
                }
            }
        }
    }
}

def checkout(folder, repoInfo) {
    repoUrl = repoInfo[0]
    branch = repoInfo[1]
    reference = repoInfo.size() < 3 ? '' : repoInfo[2]
    gitCredential = repoInfo.size() < 4 ? '' : repoInfo[3]
    stage("Checkout ${folder.split('/')[-1]}") {
        dir("${WORKSPACE}/${folder}") {
            def scmVars = checkout([$class: 'GitSCM',
                branches: [[name: "*/${branch}"]],
                doGenerateSubmoduleConfigurations: false,
                extensions: [
                    [$class: 'CloneOption',
                        honorRefspec: true,
                        noTags: true,
                        shallow: false],
                    [$class: 'CleanCheckout'],
                    [$class: 'PruneStaleBranch']],
                submoduleCfg: [],
                userRemoteConfigs: [[
                    url: repoUrl,
                    name: "origin",
                    credentialsId: gitCredential
                    ]]])
            commits."${repoUrl}" = scmVars.GIT_COMMIT
        }
    }
}