#
# Copyright (c) 2021 Airbyte, Inc., all rights reserved.
#
from abc import ABC
from typing import Any, List, Mapping, Tuple

from airbyte_cdk.models import SyncMode
from airbyte_cdk.sources import AbstractSource
from airbyte_cdk.sources.streams import Stream
from airbyte_cdk.sources.streams.http.auth import TokenAuthenticator

from .streams import (
    Activities,
    Broadcasts,
    BroadcastMetrics,
    BroadcastMetricsLinks,
    BroadcastActions,
    Campaigns,
    CampaignMetrics,
    CampaignMetricsLinks,
    CampaignActions,
)

CONNECTION_ERROR = '''Connection check failed with error: {}.
Check if the workers IP address is whitelisted in the CustomerIO
console or if acces token is set up correctly.
Also you may have configured the wrong region.'''

# Source
class SourceCustomerio(AbstractSource):

    def check_connection(self, logger, config) -> Tuple[bool, any]:
        try:
            authenticator = TokenAuthenticator(token=config["app_api_key"])
            test_stream = Activities(authenticator=authenticator,
                                     region=config["region"])
            gen = test_stream.read_records(sync_mode=SyncMode.full_refresh)
            next(gen)
            return True, None
        except Exception as e:
            # print(e)
            return False, CONNECTION_ERROR.format(e)

    def streams(self, config: Mapping[str, Any]) -> List[Stream]:
        authenticator = TokenAuthenticator(token=config["app_api_key"])
        args = {"authenticator": authenticator, "region": config["region"]}
        return [
            Activities(**args),
            Broadcasts(**args),
            BroadcastMetrics(**args),
            BroadcastMetricsLinks(**args),
            BroadcastActions(**args),
            Campaigns(**args),
            CampaignMetrics(**args),
            CampaignMetricsLinks(**args),
            CampaignActions(**args),
        ]
