// -*- mode: groovy -*-
// vim: set filetype=groovy :
import groovy.transform.Field
@Field def targetUrl = 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxxx'

properties([
        buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '7', numToKeepStr: '40')),
        parameters([
            string(name: 'releasePackageName', defaultValue: '', description: '', trim: true),
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

withWechatNotify {
    timestamps {
        node('test') {
            stage('Download && Rsync') {
                downloadAndRsync()
            }
        }
    }
}

def downloadAndRsync() {
    def releasePackageName = params.releasePackageName.toLowerCase()

    def version = ((releasePackageName =~ /(v\d+.\d+.\d+(\.\d+)?)|arsenal/)[0][0]).toString()
    def platform = ((releasePackageName =~ /(windows|android|ios|linux|mac)/)[0][0]).toString()
    def date = ((releasePackageName =~ /(\d{8})/)[0][0]).toString()
    def build_number = ((releasePackageName =~ /(?<=_)(\d+)(?=_)/)[0][0]).toString()
    def arch = releasePackageName =~ /(x86|x64|arm|arm64)/ ? ((releasePackageName =~ /(x86|x64|arm|arm64)/)[0][0]).toString() : ""

    def feature = ""
    def featureMap = [
        "xxx_rtc_sdk": [ "CompanySDK", "company_native_sdk"],
        "xxx_media_player": ["CompanyMediaPlayer", "company_media_player"],
        "xxx_rtmp_kit": ["CompanyRtmpKit", "company_rtmp_kit"]
    ]

    featureMap.each{ config, detail ->
        if (releasePackageName.contains(detail[1])) {
            feature = detail[0]
        }
    }

    date = "${date[0..3]}-${date[4..5]}-${date[6..7]}"

    withCredentials([usernamePassword(credentialsId: 'credentialsId_Placeholder', passwordVariable: 'Password_Placeholder', usernameVariable: 'Username_Placeholder'),
                     usernamePassword(credentialsId: 'credentialsId2_Placeholder', passwordVariable: 'Password2_Placeholder', usernameVariable: 'Username2_Placeholder')]) {
        withEnv(["version=${version}",
                 "platform=${platform}",
                 "buildNumber=${build_number}",
                 "date=${date}",
                 "arch=${arch}",
                 "feature=${feature}",
                 "releasePackageName=${releasePackageName}"]) {
                sh label: '', script:
                '''
                    echo "*****Get all zip files under the page*****"
                    root=`pwd`
                    mkdir -p ${root}/${version}/${feature}/${platform}/${date}/${arch} || true
                    pushd ${root}/${version}/${feature}/${platform}/${date}/${arch}
                    tmp=${releasePackageName#*8090/}
                    packagePath=${tmp%/*}
                    smbclient //serverIP_Placeholder/libs/ -U $Username2_Placeholder%$Password2_Placeholder -c "cd ${packagePath}/; prompt; mget *${buildNumber}*"

                    echo "*****Rsync local zip packages*****"
                    echo $Password_Placeholder > ${root}/rsync.passwd
                    chmod 600 ${root}/rsync.passwd
                    export RSYNC_PROXY="serverIP_Placeholder:Port_Placeholder"
                    rsync -aPvLz  --no-group --progress --password-file=${root}/rsync.passwd --bwlimit=15000 ${root}/${version} rsync@serverIP2_Placeholder::folder

                    echo "*****Link xdump info*****"
                    xdumpFile=($(ls | grep xdump.zip))
                    xdumpFile=${xdumpFile[0]}
                    xdumpFileSplitArr=(${xdumpFile//_/ })
                    accsBuildNumber=${xdumpFileSplitArr[-2]}
                    curl -d "build_number=${accsBuildNumber}&xdump_file_link=https://xxx_companylab/disk/${version}/${feature}/${platform}/${date}/${arch}/${xdumpFile}&xxx_type=sdk-nextGen" \
                    "http://xxx2_companylab:Port_Placeholder/build/version/sync_xdump_info"
                    popd
                '''
                }
        cleanWs()
    }
}

def withWechatNotify(Closure closure) {
    try {
        closure()
    } catch (Exception ex) {
        notifyWechat(ex)
        throw ex
    } finally {
        echo currentBuild.currentResult
    }
}

def notifyWechat(reason) {
    if (currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')) {
        return
    }
    head = '<font color=\\"red\\">Handle release package failed. </font> Please deal with them as soon as possible.\\n'
    url = ">[${env.RUN_DISPLAY_URL}](${env.RUN_DISPLAY_URL})" + '\\n'
    exeception = ">Info: ${reason.toString()}"
    content = "${head}${url}${exeception}"
    def payload = """
        {
            "msgtype": "markdown",
            "markdown": {
                "content": \"${content}\"
            }
        }
        """
    httpRequest httpMode: 'POST',
        acceptType: 'APPLICATION_JSON_UTF8',
        contentType: 'APPLICATION_JSON_UTF8',
        ignoreSslErrors: true, responseHandle: 'STRING',
        requestBody: payload,
        url: "${targetUrl}"
}