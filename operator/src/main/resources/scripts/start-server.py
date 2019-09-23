# Copyright 2017, 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
# Licensed under the Universal Permissive License v 1.0 as shown at
# http://oss.oracle.com/licenses/upl.

#
# This is a WLST script for starting WL server via the node manager.
#
# It users key and data files that were generated by the introspector
# for its nmConnect credentials.
#

import sys;
import base64

def getEnvVar(var):
  val=os.environ.get(var)
  if val==None:
    print "ERROR: Env var ",var, " not set."
    sys.exit(1)
  return val

domain_uid = getEnvVar('DOMAIN_UID')
server_name = getEnvVar('SERVER_NAME')
domain_name = getEnvVar('DOMAIN_NAME')
domain_path = getEnvVar('DOMAIN_HOME')
service_name = getEnvVar('SERVICE_NAME')

print 'domain path is %s' % domain_path
print 'server name is %s' % server_name

# convert b64 encoded user key file into binary

file = open('/weblogic-operator/introspector/userKeyNodeManager.secure', 'r')
contents = file.read()
file.close()
decoded=base64.decodestring(contents)

file = open('/tmp/userKeyNodeManager.secure.bin', 'wb')
file.write(decoded)
file.close()

# Connect to nodemanager and start server

try:
  nmConnect(userConfigFile='/weblogic-operator/introspector/userConfigNodeManager.secure',
            userKeyFile='/tmp/userKeyNodeManager.secure.bin',
            host='127.0.0.1',port='5556',
            domainName=domain_name,
            domainDir=domain_path,nmType='plain')
  nmStart(server_name)
  nmDisconnect()
except WLSTException, e:
  nmDisconnect()
  print e

exit()