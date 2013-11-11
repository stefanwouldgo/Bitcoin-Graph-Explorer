#! /bin/bash
bitcoind -timeout=30000 -dbcache=1000 --txindex=1 -reindex -rpcuser=user -rpcpassword=pass -rpcthreads=8 -daemon
bitcoind -rpcuser=user -rpcpassword=pass -rpcthreads=8 getblockcount

