#!/bin/bash

alfrescoViewRepoDir=../../../repo-amp
echo "pushd ${alfrescoViewRepoDir}; python test-data/javawebscripts/waitOnServer.py; python test-data/javawebscripts/regression_test_harness.py $@; popd"
pushd ${alfrescoViewRepoDir}; python test-data/javawebscripts/waitOnServer.py; python test-data/javawebscripts/regression_test_harness.py $@; popd


