package org.eigengo.sd

import org.eigengo.sd.core.{ConfigCoreConfiguration, Core}
import org.eigengo.sd.api.Api

object Rest extends App with Core with Api with ConfigCoreConfiguration
