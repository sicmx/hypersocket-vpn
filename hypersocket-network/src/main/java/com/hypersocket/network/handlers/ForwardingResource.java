package com.hypersocket.network.handlers;

import com.hypersocket.resource.AssignableResource;

public abstract class ForwardingResource extends AssignableResource {

	public abstract String getDestinationHostname();
	public abstract String getHostname();
	
}