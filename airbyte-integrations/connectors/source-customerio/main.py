#
# Copyright (c) 2021 Airbyte, Inc., all rights reserved.
#


import sys

from airbyte_cdk.entrypoint import launch
from source_customerio import SourceCustomerio

if __name__ == "__main__":
    source = SourceCustomerio()
    launch(source, sys.argv[1:])
