#
# Copyright (c) 2021 Airbyte, Inc., all rights reserved.
#
import requests
from abc import ABC, abstractmethod
from typing import Any, Iterable, List, Mapping, MutableMapping, Optional, Tuple

from airbyte_cdk.models import SyncMode
from airbyte_cdk.sources.streams.http import HttpStream

# Basic full refresh stream
class CustomerioStream(HttpStream):
    url_base = ""

    def __init__(self, region: str, **kwargs):
        super().__init__(**kwargs)
        self.region = region
        if region == "US":
            self.url_base = "https://beta-api.customer.io/v1/api/"
        elif region == "EU":
            self.url_base = "https://beta-api-eu.customer.io/v1/api/"
        else:
            raise ValueError("Region must be set to US or EU.")

# Basic incremental stream
class IncrementalCustomerioStream(CustomerioStream, ABC):
    state_checkpoint_interval = None

    @property
    def cursor_field(self) -> str:
        pass

    def get_updated_state(
        self,
        current_stream_state: MutableMapping[str, Any],
        latest_record: Mapping[str, Any]
    ) -> Mapping[str, Any]:
        pass


class Activities(CustomerioStream):
    """
    API docs: https://customer.io/docs/api/#operation/listActivities
    Returns all activities of different types.
    We get all event types for activities.
    """
    primary_key = "id"

    def path(self, **kwargs):
        return "activities"

    def next_page_token(
        self,
        response: requests.Response
    ) -> Optional[Mapping[str, Any]]:
        next_page_token = response.json()

        token = next_page_token.get("next")
        if token:
            return {"start": token}
        return {}
                 

    def request_params(
        self,
        stream_state: Mapping[str, Any],
        stream_slice: Mapping[str, Any] = None,
        next_page_token: Mapping[str, Any] = None,
    ) -> MutableMapping[str, Any]:

        # CustomerIO default pagination is 10, max is 100
        # (TODO: denimyftiu) change the limit before you commit.
        params = {"limit": 10}

        if next_page_token:
            params.update(next_page_token)

        return params

    def parse_response(
        self,
        response: requests.Response,
        **kwargs
    ) -> Iterable[Mapping]:
        response_json = response.json()
        yield from response_json.get("activities", [])


class Broadcasts(CustomerioStream):
    """
    API docs: https://customer.io/docs/api/#operation/listBroadcasts 
    Returns all activities of different types.
    We get all event types for activities.
    """
    name = "broadcasts"
    primary_key = "id"

    def path(self, **kwargs):
        return "broadcasts"

    def next_page_token(
        self,
        response: requests.Response
    ) -> Optional[Mapping[str, Any]]:
        pass

    def parse_response(
        self,
        response: requests.Response,
        **kwargs
    ) -> Iterable[Mapping]:
        response_json = response.json()
        yield from response_json.get("broadcasts", [])

class BroadcastMetrics(CustomerioStream):
    """
    API docs: https://customer.io/docs/api/#operation/broadcastMetrics
    """

    name = "broadcast_metrics"
    primary_key = "id"

    def path(self, stream_slice: Mapping[str, Any] = None, **kwargs):
        broadcast_id = stream_slice["broadcast_id"]
        return f"broadcasts/{broadcast_id}/metrics"

    def next_page_token(
        self,
        response: requests.Response
    ) -> Optional[Mapping[str, Any]]:
        return None

    def parse_response(
        self,
        response: requests.Response,
        **kwargs
    ) -> Iterable[Mapping]:
        yield response.json()

    def read_records(self, stream_slice: Optional[Mapping[str, Any]] = None, **kwargs) -> Iterable[Mapping[str, Any]]:
        broadcasts_stream = Broadcasts(authenticator=self.authenticator, region=self.region)
        for broadcast in broadcasts_stream.read_records(sync_mode=SyncMode.full_refresh):
            yield from super().read_records(stream_slice={"broadcast_id": broadcast["id"]}, **kwargs)


class BroadcastMetricsLinks(CustomerioStream):
    """
    API docs: https://customer.io/docs/api/#operation/broadcastLinks
    """

    name = "broadcast_metrics_links"
    primary_key = "id"

    def path(self, stream_slice: Mapping[str, Any] = None, **kwargs):
        broadcast_id = stream_slice["broadcast_id"]
        return f"broadcasts/{broadcast_id}/metrics/links"

    def next_page_token(
        self,
        response: requests.Response
    ) -> Optional[Mapping[str, Any]]:
        return None

    def parse_response(
        self,
        response: requests.Response,
        **kwargs
    ) -> Iterable[Mapping]:
        yield response.json()

    def read_records(self, stream_slice: Optional[Mapping[str, Any]] = None, **kwargs) -> Iterable[Mapping[str, Any]]:
        broadcasts_stream = Broadcasts(authenticator=self.authenticator, region=self.region)
        for broadcast in broadcasts_stream.read_records(sync_mode=SyncMode.full_refresh):
            yield from super().read_records(stream_slice={"broadcast_id": broadcast["id"]}, **kwargs)


class BroadcastActions(CustomerioStream):
    """
    API docs: https://customer.io/docs/api/#operation/broadcastActions
    """

    name = "broadcast_actions"
    primary_key = "id"

    def path(self, stream_slice: Mapping[str, Any] = None, **kwargs):
        broadcast_id = stream_slice["broadcast_id"]
        return f"broadcasts/{broadcast_id}/actions"

    def next_page_token(
        self,
        response: requests.Response
    ) -> Optional[Mapping[str, Any]]:
        return None

    def parse_response(
        self,
        response: requests.Response,
        **kwargs
    ) -> Iterable[Mapping]:
        yield response.json()

    def read_records(self, stream_slice: Optional[Mapping[str, Any]] = None, **kwargs) -> Iterable[Mapping[str, Any]]:
        broadcasts_stream = Broadcasts(authenticator=self.authenticator, region=self.region)
        for broadcast in broadcasts_stream.read_records(sync_mode=SyncMode.full_refresh):
            yield from super().read_records(stream_slice={"broadcast_id": broadcast["id"]}, **kwargs)


class Campaigns(CustomerioStream):
    """
    API docs: https://customer.io/docs/api/#operation/listCampaigns
    """
    name = "campaigns"
    primary_key = "id"

    def path(self, **kwargs):
        return "campaigns"

    def next_page_token(
        self,
        response: requests.Response
    ) -> Optional[Mapping[str, Any]]:
        pass

    def parse_response(
        self,
        response: requests.Response,
        **kwargs
    ) -> Iterable[Mapping]:
        response_json = response.json()
        yield from response_json.get("campaigns", [])

class CampaignMetrics(CustomerioStream):
    """
    API docs: https://customer.io/docs/api/#operation/campaignMetrics
    """

    name = "campaign_metrics"
    primary_key = "id"

    def path(self, stream_slice: Mapping[str, Any] = None, **kwargs):
        campaign_id = stream_slice["campaign_id"]
        return f"campaigns/{campaign_id}/metrics"

    def next_page_token(
        self,
        response: requests.Response
    ) -> Optional[Mapping[str, Any]]:
        return None

    def parse_response(
        self,
        response: requests.Response,
        **kwargs
    ) -> Iterable[Mapping]:
        yield response.json()

    def read_records(self, stream_slice: Optional[Mapping[str, Any]] = None, **kwargs) -> Iterable[Mapping[str, Any]]:
        campaigns_stream = Campaigns(authenticator=self.authenticator, region=self.region)
        for campaign in campaigns_stream.read_records(sync_mode=SyncMode.full_refresh):
            yield from super().read_records(stream_slice={"campaign_id": campaign["id"]}, **kwargs)


class CampaignMetricsLinks(CustomerioStream):
    """
    API docs: https://customer.io/docs/api/#operation/campaignLinks
    """

    name = "campaign_metrics_links"
    primary_key = "id"

    def path(self, stream_slice: Mapping[str, Any] = None, **kwargs):
        campaign_id = stream_slice["campaign_id"]
        return f"campaigns/{campaign_id}/metrics/links"

    def next_page_token(
        self,
        response: requests.Response
    ) -> Optional[Mapping[str, Any]]:
        return None

    def parse_response(
        self,
        response: requests.Response,
        **kwargs
    ) -> Iterable[Mapping]:
        yield response.json()

    def read_records(self, stream_slice: Optional[Mapping[str, Any]] = None, **kwargs) -> Iterable[Mapping[str, Any]]:
        campaigns_stream = Campaigns(authenticator=self.authenticator, region=self.region)
        for campaign in campaigns_stream.read_records(sync_mode=SyncMode.full_refresh):
            yield from super().read_records(stream_slice={"campaign_id": campaign["id"]}, **kwargs)


class CampaignActions(CustomerioStream):
    """
    API docs: https://customer.io/docs/api/#operation/campaignActions
    """

    name = "campaign_actions"
    primary_key = "id"

    def path(self, stream_slice: Mapping[str, Any] = None, **kwargs):
        campaign_id = stream_slice["campaign_id"]
        return f"campaigns/{campaign_id}/actions"

    def next_page_token(
        self,
        response: requests.Response
    ) -> Optional[Mapping[str, Any]]:
        return None

    def parse_response(
        self,
        response: requests.Response,
        **kwargs
    ) -> Iterable[Mapping]:
        yield response.json()

    def read_records(self, stream_slice: Optional[Mapping[str, Any]] = None, **kwargs) -> Iterable[Mapping[str, Any]]:
        campaigns_stream = Campaigns(authenticator=self.authenticator, region=self.region)
        for campaign in campaigns_stream.read_records(sync_mode=SyncMode.full_refresh):
            yield from super().read_records(stream_slice={"campaign_id": campaign["id"]}, **kwargs)

